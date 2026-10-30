// TubePatch for the iPad: replacements for the app's @Patchable Kotlin functions, without a new build.
//
// The app checks GitHub every six hours and keeps a newer copy in Files → On My iPad → TubeHaus →
// patch.js; one put there by hand is used too, whichever has the higher PATCH_VERSION. Raise it
// with every change.
//
// PATCHES maps a function's full name to a JavaScript function that takes the same arguments
// and returns the same kind of value (text, a number, true/false, or a list of those). If one
// throws or returns something else, the app ignores it and the built-in Kotlin code runs.
//
// The functions that can be replaced (more get @Patchable as needed):
//   app.tubehaus.ui.duration(sec)              "12:34" from seconds
//   app.tubehaus.ui.ago(epochSeconds)          "3 days ago"
//   app.tubehaus.ui.compact(n)                 "1.2M" from a count
//   app.tubehaus.rec.Learn.tokens(title, keywords)              the words that describe a video
//   app.tubehaus.rec.Learn.engagement(watched, length, liked, disliked)  how much a view says you liked it
//
// Example (not active): show view counts in full with commas.
//   "app.tubehaus.ui.compact": function (n) { return n.toLocaleString("en-US"); },

var PATCH_VERSION = 0;

var PATCHES = {
};
