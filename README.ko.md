# TubeHaus (튜브하우스)
한국인이 만들었어요!
[![Featured in selfh.st Weekly](https://img.shields.io/badge/featured%20in-selfh.st%20weekly-2ea44f)](https://selfh.st/weekly/2026-10-23/)

[English](README.md) · **한국어**

## 면책 조항

> [!CAUTION]
> **TubeHaus는 개인이 만든 무료 오픈소스 소프트웨어예요. 유튜브나 구글과 제휴하거나 승인·후원을 받은 프로젝트가 아니에요.** "YouTube"는 Google LLC의 상표예요.
>
> - TubeHaus는 영상을 저장하거나 올리거나 배포하지 않아요. 영상은 유튜브에서 내 기기(앱)나 내가 직접 운영하는 서버(서버판)로 바로 와요.
> - **내려받은 영상은 본인이 개인적으로 보는 용도로만 쓰세요.** 영상의 저작권은 원래 주인에게 있어요. 공유하거나 다시 올리거나 팔거나 그 밖의 방법으로 퍼뜨리는 건 전적으로 그렇게 한 사람의 책임이고, 만든 사람은 책임지지 않아요.
> - 라이선스에 적힌 대로 "있는 그대로" 제공되며 어떤 보증도 하지 않아요. 만든 사람과 기여자는 사용 방법이나 사용으로 생기는 일에 책임지지 않아요.
> - 사는 곳의 법과 연결하는 서비스의 약관에 맞게 쓰는 건 사용하는 사람의 책임이에요.
> - 만든 사람은 공개 서버나 그 밖의 서비스를 운영하지 않아요.
> - **권리자분들께:** 이 저장소의 내용이 권리를 침해한다고 생각되면 [이슈](https://github.com/biolend83/tubehaus/issues/new)로 자세한 내용을 알려 주세요(비공개 연락이 필요하면 거기서 말씀해 주세요). 확인해서 타당하면 바로 내릴게요.

**구글 계정도, 추적도 없는 개인용 유튜브 클라이언트: 내 서버에서 돌리거나, 기기에 앱으로 깔거나.**

둘 다 생김새와 쓰는 법이 유튜브와 같고(화면 구성, 단축키), 구독과 시청 기록을 구글 계정이 아니라 내 쪽에 둬요.

## 두 가지 TubeHaus

TubeHaus는 따로 동작하는 두 가지가 있어요. 이름과 생김새는 같지만, 돌아가는 방식이 달라요.

| | 🖥️ **서버판** (이 브랜치, `main`) | 📱 **앱** (`android-app`, `windows-app`, `mac-app`, `linux-app`, `ios-app` 브랜치) |
|---|---|---|
| **설치하는 것** | 서버 한 대: 클라우드 서버, 집 PC, 라즈베리파이에 docker compose | 기기마다 앱: 안드로이드(폰, 태블릿, 안드로이드 TV / 구글 TV), Windows, macOS, 리눅스, 아이패드/아이폰(Sideloadly로 설치) |
| **보는 방법** | 아무 브라우저: 폰, 아이폰/아이패드, PC, TV. 홈 화면에 추가하면 앱처럼 써요 | 그 기기의 앱에서 |
| **유튜브와 통신하는 쪽** | 내 서버가 Invidious와 Cloudflare WARP를 거쳐서. 기기는 내 서버하고만 통신해요 | 기기가 직접, 앱 안의 자체 유튜브 엔진으로. 중간에 Invidious도 TubeHaus 서버도 없어요 |
| **유튜브가 보는 주소** | Cloudflare WARP 주소. 집이나 기기 주소가 아니에요 | 내 기기의 인터넷 주소. 다른 유튜브 앱과 같아요 |
| **구독·기록이 있는 곳** | 서버에. 그 서버를 쓰는 모든 기기와 사람이 같이 써요 | 그 기기에. 또는 앱을 내 TubeHaus 서버에 연결하면(0.5.4부터) 서버, 다른 기기와 라이브러리 하나를 같이 써요 |
| **여기에만 있는 것** | 라이브 실시간 자막(whisper.cpp), TV로 보내기, 원본 화질 저장, TV 모드, 아이폰/아이패드를 브라우저로(설치 없이) | 오프라인 저장, 백그라운드 재생과 PIP(안드로이드, 아이패드/아이폰), mpv 플레이어(PC), 기기 안에서 배우는 추천 |
| **필요한 것** | 서버와 Docker 기초 | 없음 |

**고르기 어렵다면:** 폰이나 PC 한 대에서, 오늘 바로, 아무 설정 없이 쓰고 싶다면 앱을 받으세요.
온 가족이 구독과 기록을 같이 쓰고 싶거나, 아이폰/아이패드에서 설치 없이 보거나, 유튜브가 내 기기 주소를 모르게 하고 싶다면 서버판을 돌리세요.
**둘 다?** 0.5.4부터 앱을 내 서버에 연결할 수 있어요(설정 → *TubeHaus 서버*). 라이브러리는 모든 기기와 브라우저에서 하나로 쓰고, 영상은 각 앱이 직접 재생해요.

- **📱 앱 받기 (베타):** [안드로이드 APK](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9.apk) (안드로이드 8 이상, 안드로이드 TV는 Downloader 코드 **3155410**) · [Windows](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9.msi) · macOS ([M칩](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-mac-arm64.dmg), [인텔](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-mac-x64.dmg)) · [리눅스 .deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-linux-amd64.deb) · [.rpm](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-linux-x86_64.rpm) · [아이패드/아이폰 .ipa](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-ipad.ipa) (베타, [Sideloadly](https://sideloadly.io/)로 설치) · [전체 파일](https://github.com/biolend83/tubehaus/releases/latest) (Windows portable과 ARM, 리눅스 ARM64) · 안드로이드 자동 업데이트는 [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22io.github.biolend83.tubehaus%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Fbiolend83%2Ftubehaus%22%2C%20%22author%22%3A%20%22biolend83%22%2C%20%22name%22%3A%20%22TubeHaus%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5ETubeHaus%20%5C%22%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22TubeHaus-%5C%5C%5C%5Cd%5C%22%7D%22%7D)으로.
  플랫폼별 설치 안내와 소스: [`android-app`](https://github.com/biolend83/tubehaus/tree/android-app), [`windows-app`](https://github.com/biolend83/tubehaus/tree/windows-app), [`mac-app`](https://github.com/biolend83/tubehaus/tree/mac-app), [`linux-app`](https://github.com/biolend83/tubehaus/tree/linux-app), [`ios-app`](https://github.com/biolend83/tubehaus/tree/ios-app)
- **🍎 아이패드 / 아이폰:** 같은 앱을 iOS 16+용으로 만들었어요(베타). 애플 플레이어로 4K까지, 백그라운드 재생, PIP,
  오프라인 저장, 그리고 앱을 다시 설치하지 않아도 스스로 업데이트되는 유튜브 엔진. 앱스토어에 없어서
  [.ipa](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.9/TubeHaus-0.5.9-ipad.ipa)를 **[Sideloadly](https://sideloadly.io/)**(무료, Windows/Mac)와 내 Apple ID로 설치해요.
  무료 Apple ID는 7일마다 다시 서명해야 해요. 자세한 순서: [`ios-app`](https://github.com/biolend83/tubehaus/tree/ios-app). 설치가 싫다면 사파리에서 서버판을 쓰세요.
- **🖥️ 서버판 돌리기:** 아래 [빠른 설치 (Docker)](#빠른-설치-docker)를 보세요. 이 README의 나머지는 모두 서버판 이야기예요.

서버판은 [Invidious](https://invidious.io)와 [invidious-companion](https://github.com/iv-org/invidious-companion) 위에서 동작해요.
유튜브와 데이터를 주고받는 어려운 일은 이 두 프로젝트가 하고, TubeHaus는 그 옆에 화면과 작은 서버를 붙여요.
Invidious 팀에 감사드려요.

![TubeHaus 첫 화면](docs/screenshot-home.jpg)

## 서버판이 좋은 점

**⚡ 빨라요**
- **누르자마자 열려요.** 영상 정보를 서버에 미리 받아 두고 뒤에서 새로 고쳐요. 다음에 누를 만한 영상도 미리 받아 둬요(처음 1~2초, 그다음부터는 수 밀리초)
- **라이브가 끊기지 않아요.** 서버가 플레이어보다 먼저 영상 조각을 받아 두고, 정확한 길이로 이어 붙여서 재생 막대가 튀지 않아요
- **늘 최고 화질이에요.** 화질을 알아서 낮추지 않아요. 60프레임과, 기기가 하드웨어로 부드럽게 푸는 코덱을 먼저 골라요. 8K도 브라우저 버퍼에 맞게 조절해요

**🔒 기록이 밖으로 새지 않아요**
- **기기가 구글과 직접 통신하지 않아요.** 영상, 썸네일, 페이지가 전부 내 서버를 거쳐요. 구글 계정, 쿠키, 유튜브 스크립트가 브라우저에 들어오지 않아요
- **유튜브는 집 IP를 몰라요.** 서버가 유튜브에 보내는 요청은 모두 Cloudflare WARP를 거쳐 나가요
- **계정도 추적도 없어요.** 구독, 시청 기록, 이어보기 위치, 재생목록은 내 서버의 파일이고, 우리 집 사람만 같이 써요

**☁️ 클라우드 서버에서도 돌아가요**
- 클라우드 서버 주소는 유튜브에서 "봇이 아닌지 확인하려면 로그인하세요"가 자주 떠요. TubeHaus는 유튜브 요청을 **Cloudflare WARP(프록시 모드)**로 내보내고, 요청이 실패하기 시작하면 WARP를 다시 연결해 다시 시도해요(횟수 제한 있음). 그래서 값싼 클라우드 서버에서도 돌아가요

**🎙️ 라이브 실시간 자막**
- 유튜브는 라이브 방송에 자막을 주지 않아요. TubeHaus는 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)로 소리를 받아써서 재생보다 몇 초 앞서 자막을 만들어요. CPU 코어 하나면 돼요

**🌿 차분 모드**
- 설정의 스위치 하나로 유튜브를 다시 "도구"로 만들어요. 홈에는 구독 채널의 새 영상만 나오고, 쇼츠, 인기, 추천 영상은 어디서도 안 보이고, 영상이 끝나도 다음 영상이 저절로 재생되지 않고, 댓글은 눌러야 펼쳐져요. 검색은 그대로라서, 보려던 것만 보고 나올 수 있어요

**🧠 내 서버에서 배우는 쇼츠 추천**
- 쇼츠를 볼 때 몇 초 봤는지, 반복해서 봤는지, 좋아요를 눌렀는지, 바로 넘겼는지를 보고 취향을 배워요. 채널과 주제(태그, 해시태그, 장르)마다 점수를 매겨 내 서버의 작은 파일에 저장하고, 구독 채널, 좋아하는 채널, 좋아하는 주제, 인기 쇼츠 중에서 다음 쇼츠를 골라요. 네 번째 칸마다 새로운 것도 섞어요. 구글 계정이 필요 없고 정보가 서버 밖으로 나가지 않아요. 시청 기록 화면에서 언제든 초기화할 수 있어요

**🌍 13개 언어:** 한국어 · English · 日本語 · 简体中文 · Español · Português · Français · Deutsch · Русский · हिन्दी · Bahasa Indonesia · Türkçe · বাংলা.
처음 접속할 때 고르고(고르기 전에는 영어), 계정 메뉴나 설정의 **언어**에서 언제든 바꿀 수 있어요.

## 그 밖의 기능

- 유튜브와 같은 화면: 홈, 검색, 채널, 재생목록, 구독, 기록, 보관함, 인기 급상승
- [Shaka Player](https://github.com/shaka-project/shaka-player) 기반 플레이어: 챕터, 탐색 미리보기, SponsorBlock, 재생 속도, 수면 타이머, 통계, 미니 플레이어, 영화관 모드, 앰비언트 모드, 잠금 화면과 블루투스 버튼 조작
- **모든 기기가 같은 라이브러리:** 구독(RSS 기반 피드), 시청 기록, 이어보기, 나중에 볼 동영상, 좋아요, 재생목록
- **TV로 보내기:** 폰에서 영상을 고르면 TV에서 재생돼요(크롬캐스트 필요 없음)
- **고화질 저장:** 1080p와 4K는 영상과 소리가 따로 와요. 서버가 둘 다 받아 ffmpeg로 합쳐 줘요
- **번역 자막:** 내 언어 자막이 없는 영상도 자막을 볼 수 있어요
- **TV 모드:** TV 브라우저에서 리모컨(방향키, 확인, 뒤로)만으로 조작해요
- 싫어요 수(Return YouTube Dislike), 댓글, 스크립트

브라우저가 직접 하는 선택 기능: SponsorBlock(영상 ID 대신 해시 앞부분만 보내요), Return YouTube Dislike.

## 필요한 것

- **서버:** Docker와 docker compose가 있는 리눅스, x86-64 또는 ARM64(오라클 클라우드 무료 ARM 서버로 충분해요)
- **CPU / 메모리:** 2코어 이상, 전체 합쳐 메모리 약 4GB. 라이브 자막을 켜고 라이브를 볼 때만 코어 하나를 더 써요
- **디스크:** 약 10~20GB, 대부분 Docker 이미지예요. 영상은 저장하지 않고 흘려보내서 써도 늘지 않아요
- **HTTPS용 주소:** 도메인, 또는 sslip.io 같은 무료 와일드카드 DNS 이름. 집 안 네트워크에서만 쓰면 HTTP도 돼요

## 빠른 설치 (Docker)

```sh
git clone https://github.com/biolend83/tubehaus && cd tubehaus
cp .env.example .env        # 도메인과 비밀값 세 개를 넣어요
docker compose pull         # 미리 빌드된 이미지 (amd64, arm64)
docker compose up -d
```

Caddy, WARP가 들어 있는 TubeHaus 서버, 라이브용 PO 토큰 제공기, 라이브 자막용 whisper.cpp,
공식 Invidious + companion + PostgreSQL 이미지가 한 번에 올라와요. 끝나면 도메인을 열면 돼요.

**외부에 열기 전에 로그인을 붙이세요.** TubeHaus에는 계정 기능이 없어서, 주소를 아는 사람은 누구나 같은 라이브러리를 봐요.
가장 쉬운 방법은 내장 비밀번호예요. `.env`에 `TUBEHAUS_AUTH=basic`, `TUBEHAUS_USER`, `TUBEHAUS_PASSWORD_HASH`를 넣으세요.
해시는 `docker compose run --rm caddy caddy hash-password`로 만들고, `.env`에서는 `$`를 모두 `$$`로 써야 해요.
Authelia, Authentik, 패스키 게이트 같은 것을 `forward_auth`로 앞에 둬도 돼요.

**잘 돌아가는지 점검:**

```sh
docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor
```

WARP, 유튜브 연결, Invidious, 실제 영상 조회, 선택 기능(yt-dlp, ffmpeg, whisper)을 한 줄씩 점검해요.
문제가 있으면 [문제 해결](docs/TROUBLESHOOTING.md)(영어), 업데이트와 백업은 [업데이트와 백업](docs/UPGRADING.md)(영어)을 보세요.

직접 설치(Docker 없이)와 설정 항목 전체는 [영어 README](README.md#manual-setup)에 있어요.

## 공개 서버로 열기

설치 없이 써 보게 해 주고 싶다면 `.env`에 `TUBEHAUS_PUBLIC=1`을 넣으세요.
- **방문자 기록은 각자의 브라우저에 저장돼요.** 구독, 시청 기록, 이어보기, 재생목록이 서버에 남지 않아서, 모르는 사람끼리 서로의 기록을 볼 수 없어요
- **서버는 저장 없는 일만 해요:** 피드 받아오기, 가져오기 파일 읽기, 내보내기 변환, 영상 재생
- **꺼지는 기능:** TV로 보내기, 서버 다운로드(낯선 사람끼리 공유되기 때문), 쇼츠 추천 학습
- **사람이 몰려도 버티게:** `.env`에서 동시 시청자 수(`TUBEHAUS_MAX_VIEWERS`), 최고 화질(`TUBEHAUS_MAX_HEIGHT`, 예: 1080), 한 사람당 분당 요청 수(`TUBEHAUS_RATE_PER_MIN`, 공개 서버 기본 120)를 정할 수 있어요. 자리가 차면 새로 온 사람에게 "바빠요, 잠시 뒤에"라고 알려주고, 이미 보는 사람은 끊기지 않아요. 같은 영상이나 같은 채널을 여러 명이 동시에 열면 유튜브에는 한 번만 물어보고, 라이브는 몇 명이 보든 한 번만 받아와요
- **바로 쓰는 공개 서버 구성:** `deploy/docker-compose.public.yml`에 다 들어 있어요. 들어오면 먼저 봇 확인 화면(Anubis, 몇 초 계산)이 뜨고, 그다음 계정 만들기(사용자 이름과 비밀번호만, 이메일 없음)를 해야 볼 수 있어요. 비밀번호는 60만 번 해싱해서 저장하고, 가입과 로그인은 네트워크마다 횟수를 제한해요
- **열기 전에:** 영상이 서버를 거쳐서 데이터 사용량이 커요. 사람이 많으면 유튜브에 더 빨리 막힐 수 있어요. 내장 비밀번호는 끄고 두세요

## 자주 묻는 질문

**Invidious를 대체하나요?**
아니요, 같이 돌아가요. 유튜브에서 가져오는 일은 Invidious와 companion이 하고, TubeHaus는 유튜브 같은 화면과,
라이브, 라이브 자막, 공유 라이브러리, TV로 보내기, 저장, WARP를 더하는 서버예요. docker compose가 공식 Invidious 이미지를 같이 띄워 줘요.

**이미 쓰는 Invidious(또는 Yattee, FreeTube)와 같이 쓸 수 있나요?**
네. 직접 설치 방법대로 기존 Invidious를 가리키게 하면 돼요. 같은 Invidious를 쓰는 앱들은 그대로 동작해요.

**영상은 어디서 오나요? 서버에 저장되나요?**
전부 유튜브에서 와요. 라이브러리(구독, 기록, 재생목록)와, 저장할 때 잠깐 쓰는 임시 파일 말고는 아무것도 저장하지 않아요.

**추천 알고리즘이 있나요?**
쇼츠에는 있어요. 전부 내 서버에서 돌아가요(위의 "내 서버에서 배우는 쇼츠 추천"). 일반 영상은 구독 피드, 인기와 분야별 탭, 영상마다 유튜브가 주는 관련 영상 목록을 보여줘요. 구글 계정이 없으니 구글이 내 시청을 학습하는 일은 없어요.

**연령 제한 영상은요?**
대부분 재생되지 않아요. 나이 확인을 통과할 로그인 계정이 없기 때문이에요. Invidious도 마찬가지예요.

**구독을 가져오거나, 내 데이터를 빼낼 수 있나요?**
네. 설정 → 내 데이터(또는 구독 관리 화면)의 **파일에서 가져오기**로 구글 Takeout(구독 CSV, `watch-history.json` 시청 기록),
Invidious, NewPipe, FreeTube 내보내기 파일, OPML, TubeHaus 백업을 가져올 수 있어요. **내보내기**로는 TubeHaus 전체 백업
(구독, 시청 기록, 이어보기, 재생목록, 좋아요), Invidious 형식, Takeout과 같은 CSV, RSS 리더용 OPML을 받을 수 있어요.
내 데이터가 TubeHaus에 갇히는 일은 없어요.

**밖으로 정보를 보내나요?**
아니요. 계정도 원격 수집도 없어요. 밖으로 나가는 요청은 유튜브(Invidious와 WARP를 거쳐서)와, 브라우저의 선택 기능(SponsorBlock, Return YouTube Dislike)뿐이에요.

**폰과 TV에서는 어떻게 접속하나요?**
웹앱이에요. 브라우저에서 내 주소를 열거나, "홈 화면에 추가"로 앱처럼 설치하면 돼요. TV 브라우저에서는 리모컨으로 조작하는 TV 모드가 켜져요.
안드로이드에서는 서버 없이 [TubeHaus 앱](https://github.com/biolend83/tubehaus/tree/android-app)을 쓸 수도 있어요.

## 함께하기

- **질문과 설치 도움:** [Discussions](https://github.com/biolend83/tubehaus/discussions) (`--doctor` 결과를 같이 붙여 주세요)
- **버그와 기능 제안:** [Issues](https://github.com/biolend83/tubehaus/issues/new/choose)
- **기여:** [CONTRIBUTING.md](CONTRIBUTING.md)(영어). 번역 고치기가 첫 기여로 좋아요
- **보안 문제:** 공개 이슈 말고 [비공개로 알려 주세요](SECURITY.md)

## 고마운 사람들

코드, 번역, 테스트, 아이디어로 TubeHaus를 더 좋게 만들어 준 분들이에요.

**코드와 디자인**
- <img src="https://github.com/Aaqibhafeezkhan.png?size=48" width="24" height="24" alt=""> [@Aaqibhafeezkhan](https://github.com/Aaqibhafeezkhan): 집과 재생 버튼 아이콘, 재생 속도 기억, nginx 예시, 스페인어 다듬기 (#28, #29, #30, #31)
- <img src="https://github.com/bh-official.png?size=48" width="24" height="24" alt=""> [@bh-official](https://github.com/bh-official): 마우스를 올리면 정확한 업로드 날짜, 업로드 시각 테스트 (#26, #27)
- <img src="https://github.com/onyks-os.png?size=48" width="24" height="24" alt=""> [@onyks-os](https://github.com/onyks-os): 첫 기여, 설치 안내 수정 (#1)

**번역**
- <img src="https://github.com/SakarPaisen.png?size=48" width="24" height="24" alt=""> [@SakarPaisen](https://github.com/SakarPaisen): 터키어 (#8)
- <img src="https://github.com/rahul05ranjan.png?size=48" width="24" height="24" alt=""> [@rahul05ranjan](https://github.com/rahul05ranjan): 벵골어, 원어민의 힌디어 다듬기 (#16, #17)

**아이디어와 버그 제보**
- <img src="https://github.com/JamesMowery.png?size=48" width="24" height="24" alt=""> [@JamesMowery](https://github.com/JamesMowery): 파일 가져오기와 데이터 내보내기 제안(v0.4.0), 새 영상의 업로드 시각 오류 제보 (#14, #15, #20)

**앱 테스트** ([r/DigitalEscapeTools](https://www.reddit.com/r/DigitalEscapeTools/))
- [u/Proper_Witness9173](https://www.reddit.com/user/Proper_Witness9173/): 밝기·볼륨 제스처, 태블릿 화면, 영상 옆 댓글, PiP 등 0.3.8의 대부분
- [u/DryYam2258](https://www.reddit.com/user/DryYam2258/): 첫 Mac 테스터. 글자 크기, 구독 지우기, 채널 사진 요청과 구독 그룹 제안
- [u/Gloomy_Acadia_445](https://www.reddit.com/user/Gloomy_Acadia_445/): 리눅스 요청으로 .deb, .rpm, tar.gz가 생겼어요
- [u/Victor_Orbis35](https://www.reddit.com/user/Victor_Orbis35/): Windows "mpv is missing" 제보. 원인은 빠진 Vulkan 런타임이었어요 (0.3.5에서 수정)

유용한 이슈를 열었거나, PR을 보냈거나, 앱을 테스트해 주셨나요? 여기에 함께 올려 드릴게요.

## 라이선스

AGPL-3.0, Invidious와 같아요. 위의 [면책 조항](#면책-조항)도 함께 봐 주세요.
