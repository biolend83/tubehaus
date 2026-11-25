// V8 for the app's SABR engine (app.tubehaus.android.V8Runtime): one isolate and context per runtime, every call
// on the runtime's one thread. Host functions: __h(name, ...args) calls V8Runtime.call, __ha(name, ...args) returns
// a promise that V8Runtime settles later (settle). Values: string, number, boolean, null, Uint8Array <-> byte[].
#include <jni.h>
#include <libplatform/libplatform.h>
#include <v8.h>

#include <map>
#include <memory>
#include <mutex>
#include <string>

namespace {

JavaVM* gVm = nullptr;
std::unique_ptr<v8::Platform> gPlatform;
std::once_flag gInit;

jclass cString, cDouble, cBoolean, cNumber, cObject, cBytes;
jmethodID mDoubleNew, mBooleanNew, mNumberDouble, mBooleanValue;
jmethodID mCall, mCallAsync, mSettled;

struct Engine {
  v8::Isolate* iso = nullptr;
  v8::ArrayBuffer::Allocator* alloc = nullptr;
  v8::Global<v8::Context> ctx;
  jobject host = nullptr;  // the V8Runtime (global ref)
  std::map<int, v8::Global<v8::Promise::Resolver>> pending;
  int next = 1;
};

JNIEnv* env() {
  JNIEnv* e = nullptr;
  gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6);
  return e;
}

std::string str(v8::Isolate* iso, v8::Local<v8::Value> v) {
  v8::String::Utf8Value u(iso, v);
  return *u ? std::string(*u, u.length()) : std::string();
}

jstring jstr(JNIEnv* e, const std::string& s) {
  // Java's NewStringUTF wants modified UTF-8; go through UTF-16 for anything outside the BMP
  std::u16string w;
  for (size_t i = 0; i < s.size();) {
    unsigned char c = s[i];
    uint32_t cp; int n;
    if (c < 0x80) { cp = c; n = 1; } else if ((c >> 5) == 6) { cp = c & 0x1f; n = 2; } else if ((c >> 4) == 14) { cp = c & 0x0f; n = 3; } else { cp = c & 0x07; n = 4; }
    for (int k = 1; k < n && i + k < s.size(); k++) cp = (cp << 6) | (s[i + k] & 0x3f);
    i += n;
    if (cp >= 0x10000) { cp -= 0x10000; w.push_back(char16_t(0xD800 + (cp >> 10))); w.push_back(char16_t(0xDC00 + (cp & 0x3ff))); }
    else w.push_back(char16_t(cp));
  }
  return e->NewString(reinterpret_cast<const jchar*>(w.data()), jsize(w.size()));
}

v8::Local<v8::String> v8str(v8::Isolate* iso, JNIEnv* e, jstring s) {
  const jchar* c = e->GetStringChars(s, nullptr);
  auto r = v8::String::NewFromTwoByte(iso, reinterpret_cast<const uint16_t*>(c), v8::NewStringType::kNormal, e->GetStringLength(s)).ToLocalChecked();
  e->ReleaseStringChars(s, c);
  return r;
}

jobject toJava(JNIEnv* e, v8::Isolate* iso, v8::Local<v8::Value> v) {
  if (v.IsEmpty() || v->IsNullOrUndefined()) return nullptr;
  if (v->IsString()) {
    v8::Local<v8::String> s = v.As<v8::String>();
    int n = s->Length();
    std::u16string w(n, u'\0');
    s->Write(iso, reinterpret_cast<uint16_t*>(w.data()), 0, n, v8::String::NO_NULL_TERMINATION);
    return e->NewString(reinterpret_cast<const jchar*>(w.data()), n);
  }
  if (v->IsNumber()) return e->NewObject(cDouble, mDoubleNew, v.As<v8::Number>()->Value());
  if (v->IsBoolean()) return e->NewObject(cBoolean, mBooleanNew, jboolean(v->IsTrue()));
  if (v->IsArrayBufferView()) {
    auto view = v.As<v8::ArrayBufferView>();
    size_t n = view->ByteLength();
    jbyteArray a = e->NewByteArray(jsize(n));
    if (n) {
      std::string tmp(n, '\0');
      view->CopyContents(tmp.data(), n);
      e->SetByteArrayRegion(a, 0, jsize(n), reinterpret_cast<const jbyte*>(tmp.data()));
    }
    return a;
  }
  return jstr(e, str(iso, v));
}

v8::Local<v8::Value> toJs(JNIEnv* e, v8::Isolate* iso, jobject o) {
  if (!o) return v8::Null(iso);
  if (e->IsInstanceOf(o, cString)) return v8str(iso, e, static_cast<jstring>(o));
  if (e->IsInstanceOf(o, cBoolean)) return v8::Boolean::New(iso, e->CallBooleanMethod(o, mBooleanValue));
  if (e->IsInstanceOf(o, cNumber)) return v8::Number::New(iso, e->CallDoubleMethod(o, mNumberDouble));
  if (e->IsInstanceOf(o, cBytes)) {
    auto a = static_cast<jbyteArray>(o);
    jsize n = e->GetArrayLength(a);
    auto buf = v8::ArrayBuffer::New(iso, size_t(n));
    if (n) e->GetByteArrayRegion(a, 0, n, static_cast<jbyte*>(buf->Data()));
    return v8::Uint8Array::New(buf, 0, size_t(n));
  }
  return v8::Undefined(iso);
}

jobjectArray argsOf(JNIEnv* e, const v8::FunctionCallbackInfo<v8::Value>& info, int from) {
  int n = info.Length() - from;
  if (n < 0) n = 0;
  jobjectArray a = e->NewObjectArray(n, cObject, nullptr);
  for (int i = 0; i < n; i++) {
    jobject o = toJava(e, info.GetIsolate(), info[i + from]);
    e->SetObjectArrayElement(a, i, o);
    if (o) e->DeleteLocalRef(o);
  }
  return a;
}

Engine* engineOf(const v8::FunctionCallbackInfo<v8::Value>& info) {
  return static_cast<Engine*>(info.Data().As<v8::External>()->Value());
}

// a Java exception from a host function becomes a JavaScript one
bool rethrow(JNIEnv* e, v8::Isolate* iso) {
  if (!e->ExceptionCheck()) return false;
  jthrowable t = e->ExceptionOccurred();
  e->ExceptionClear();
  jclass tc = e->GetObjectClass(t);
  jmethodID ts = e->GetMethodID(tc, "toString", "()Ljava/lang/String;");
  auto js = static_cast<jstring>(e->CallObjectMethod(t, ts));
  if (e->ExceptionCheck()) e->ExceptionClear();
  iso->ThrowException(v8::Exception::Error(js ? v8str(iso, e, js) : v8::String::NewFromUtf8Literal(iso, "host error")));
  return true;
}

void hostCall(const v8::FunctionCallbackInfo<v8::Value>& info) {
  Engine* g = engineOf(info);
  JNIEnv* e = env();
  e->PushLocalFrame(64);
  auto name = static_cast<jstring>(toJava(e, info.GetIsolate(), info[0]));
  jobject r = e->CallObjectMethod(g->host, mCall, name, argsOf(e, info, 1));
  if (!rethrow(e, info.GetIsolate())) info.GetReturnValue().Set(toJs(e, info.GetIsolate(), r));
  e->PopLocalFrame(nullptr);
}

void hostAsync(const v8::FunctionCallbackInfo<v8::Value>& info) {
  Engine* g = engineOf(info);
  v8::Isolate* iso = info.GetIsolate();
  auto ctx = iso->GetCurrentContext();
  auto res = v8::Promise::Resolver::New(ctx).ToLocalChecked();
  int id = g->next++;
  g->pending[id].Reset(iso, res);
  JNIEnv* e = env();
  e->PushLocalFrame(64);
  auto name = static_cast<jstring>(toJava(e, iso, info[0]));
  e->CallVoidMethod(g->host, mCallAsync, jint(id), name, argsOf(e, info, 1));
  rethrow(e, iso);
  e->PopLocalFrame(nullptr);
  info.GetReturnValue().Set(res->GetPromise());
}

void settledCb(const v8::FunctionCallbackInfo<v8::Value>& info) {
  // __settled(id, error or undefined): a promise the app waits on (V8Runtime.await) has settled
  Engine* g = engineOf(info);
  JNIEnv* e = env();
  e->PushLocalFrame(16);
  jobject err = info[1]->IsUndefined() ? nullptr : toJava(e, info.GetIsolate(), info[1]);
  e->CallVoidMethod(g->host, mSettled, jint(info[0].As<v8::Number>()->Value()), static_cast<jstring>(err));
  if (e->ExceptionCheck()) e->ExceptionClear();
  e->PopLocalFrame(nullptr);
}

void throwJava(JNIEnv* e, const std::string& msg) {
  e->ThrowNew(e->FindClass("java/lang/RuntimeException"), msg.c_str());
}

std::string describe(v8::Isolate* iso, v8::TryCatch& tc) {
  std::string m = str(iso, tc.Exception());
  auto st = tc.StackTrace(iso->GetCurrentContext());
  if (!st.IsEmpty()) m += "\n" + str(iso, st.ToLocalChecked());
  return m;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  gVm = vm;
  JNIEnv* e = env();
  auto g = [&](const char* n) { return static_cast<jclass>(e->NewGlobalRef(e->FindClass(n))); };
  cString = g("java/lang/String"); cDouble = g("java/lang/Double"); cBoolean = g("java/lang/Boolean");
  cNumber = g("java/lang/Number"); cObject = g("java/lang/Object"); cBytes = g("[B");
  mDoubleNew = e->GetMethodID(cDouble, "<init>", "(D)V");
  mBooleanNew = e->GetMethodID(cBoolean, "<init>", "(Z)V");
  mNumberDouble = e->GetMethodID(cNumber, "doubleValue", "()D");
  mBooleanValue = e->GetMethodID(cBoolean, "booleanValue", "()Z");
  jclass rt = e->FindClass("app/tubehaus/android/V8Runtime");
  mCall = e->GetMethodID(rt, "call", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
  mCallAsync = e->GetMethodID(rt, "callAsync", "(ILjava/lang/String;[Ljava/lang/Object;)V");
  mSettled = e->GetMethodID(rt, "settled", "(ILjava/lang/String;)V");
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_app_tubehaus_android_V8Runtime_create(JNIEnv* e, jobject self, jlong heapMb) {
  std::call_once(gInit, [] {
    v8::V8::SetFlagsFromString("--single-threaded-gc --no-expose-wasm");
    gPlatform = v8::platform::NewDefaultPlatform(1);
    v8::V8::InitializePlatform(gPlatform.get());
    v8::V8::Initialize();
  });
  auto* g = new Engine();
  g->host = e->NewGlobalRef(self);
  g->alloc = v8::ArrayBuffer::Allocator::NewDefaultAllocator();
  v8::Isolate::CreateParams p;
  p.array_buffer_allocator = g->alloc;
  p.constraints.ConfigureDefaultsFromHeapSize(0, size_t(heapMb) << 20);
  g->iso = v8::Isolate::New(p);
  g->iso->SetMicrotasksPolicy(v8::MicrotasksPolicy::kExplicit);
  v8::Isolate::Scope is(g->iso);
  v8::HandleScope hs(g->iso);
  auto ctx = v8::Context::New(g->iso);
  v8::Context::Scope cs(ctx);
  auto data = v8::External::New(g->iso, g);
  auto global = ctx->Global();
  auto fn = [&](const char* name, v8::FunctionCallback cb) {
    global->Set(ctx, v8::String::NewFromUtf8(g->iso, name).ToLocalChecked(), v8::Function::New(ctx, cb, data).ToLocalChecked()).Check();
  };
  fn("__h", hostCall);
  fn("__ha", hostAsync);
  fn("__settled", settledCb);
  g->ctx.Reset(g->iso, ctx);
  return reinterpret_cast<jlong>(g);
}

JNIEXPORT jobject JNICALL Java_app_tubehaus_android_V8Runtime_eval(JNIEnv* e, jobject, jlong ptr, jstring code, jstring name) {
  auto* g = reinterpret_cast<Engine*>(ptr);
  v8::Isolate::Scope is(g->iso);
  v8::HandleScope hs(g->iso);
  auto ctx = g->ctx.Get(g->iso);
  v8::Context::Scope cs(ctx);
  v8::TryCatch tc(g->iso);
  v8::ScriptOrigin origin(v8str(g->iso, e, name));
  v8::Local<v8::Script> s;
  v8::Local<v8::Value> r;
  bool ok = v8::Script::Compile(ctx, v8str(g->iso, e, code), &origin).ToLocal(&s) && s->Run(ctx).ToLocal(&r);
  g->iso->PerformMicrotaskCheckpoint();
  if (!ok) { throwJava(e, describe(g->iso, tc)); return nullptr; }
  return toJava(e, g->iso, r);
}

JNIEXPORT void JNICALL Java_app_tubehaus_android_V8Runtime_settle(JNIEnv* e, jobject, jlong ptr, jint id, jobject value, jstring error) {
  auto* g = reinterpret_cast<Engine*>(ptr);
  v8::Isolate::Scope is(g->iso);
  v8::HandleScope hs(g->iso);
  auto ctx = g->ctx.Get(g->iso);
  v8::Context::Scope cs(ctx);
  auto it = g->pending.find(id);
  if (it == g->pending.end()) return;
  auto res = it->second.Get(g->iso);
  g->pending.erase(it);
  if (error) res->Reject(ctx, v8::Exception::Error(v8str(g->iso, e, error))).Check();
  else res->Resolve(ctx, toJs(e, g->iso, value)).Check();
  g->iso->PerformMicrotaskCheckpoint();
}

JNIEXPORT void JNICALL Java_app_tubehaus_android_V8Runtime_destroy(JNIEnv* e, jobject, jlong ptr) {
  auto* g = reinterpret_cast<Engine*>(ptr);
  {
    v8::Isolate::Scope is(g->iso);
    g->pending.clear();
    g->ctx.Reset();
  }
  g->iso->Dispose();
  delete g->alloc;
  e->DeleteGlobalRef(g->host);
  delete g;
}

}  // extern "C"
