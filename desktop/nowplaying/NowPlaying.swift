// macOS Control Center and the media keys for TubeHaus: what plays (title, artist, cover, position)
// and its buttons. Built by Gradle into the jar as /nowplaying/libnowplaying.dylib and loaded into
// the app's own process through JNA (MacMediaControls.kt), so Control Center names TubeHaus.
// A helper process wouldn't do: Now Playing belongs to the process that sets it.
import AppKit
import MediaPlayer

/// a button pressed: 1 play, 2 pause, 3 play/pause, 4 next, 5 previous, 6 go to [seconds]
public typealias Command = @convention(c) (Int32, Double) -> Void

private var handler: Command?
private var info: [String: Any] = [:]
private var artURL = ""
private var artwork: MPMediaItemArtwork?

private func onMain(_ run: @escaping () -> Void) {
    if Thread.isMainThread { run() } else { DispatchQueue.main.async(execute: run) }
}

@_cdecl("np_start")
public func npStart(_ cb: Command) {
    handler = cb
    onMain {
        let c = MPRemoteCommandCenter.shared()
        func on(_ cmd: MPRemoteCommand, _ code: Int32) {
            cmd.isEnabled = true
            cmd.addTarget { _ in handler?(code, 0); return .success }
        }
        on(c.playCommand, 1)
        on(c.pauseCommand, 2)
        on(c.togglePlayPauseCommand, 3)
        on(c.nextTrackCommand, 4)
        on(c.previousTrackCommand, 5)
        c.changePlaybackPositionCommand.isEnabled = true
        c.changePlaybackPositionCommand.addTarget { e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            handler?(6, e.positionTime)
            return .success
        }
    }
}

/// what plays now; [rate] 0 is paused; [art] the cover's address ("" none), fetched here once per address
@_cdecl("np_show")
public func npShow(_ title: UnsafePointer<CChar>, _ artist: UnsafePointer<CChar>, _ duration: Double,
                   _ elapsed: Double, _ rate: Double, _ art: UnsafePointer<CChar>) {
    let t = String(cString: title), a = String(cString: artist), u = String(cString: art)
    onMain {
        info = [
            MPMediaItemPropertyTitle: t,
            MPMediaItemPropertyArtist: a,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: rate,
        ]
        if u != artURL { artURL = u; artwork = nil; loadArt(u) }
        if let w = artwork { info[MPMediaItemPropertyArtwork] = w }
        let c = MPNowPlayingInfoCenter.default()
        c.nowPlayingInfo = info
        // macOS shows the app in Control Center by this state, not by the info alone
        c.playbackState = rate > 0 ? .playing : .paused
    }
}

/// nothing plays: gone from Control Center
@_cdecl("np_clear")
public func npClear() {
    onMain {
        info = [:]
        artURL = ""
        artwork = nil
        let c = MPNowPlayingInfoCenter.default()
        c.nowPlayingInfo = nil
        c.playbackState = .stopped
    }
}

private func loadArt(_ u: String) {
    guard !u.isEmpty, let url = URL(string: u) else { return }
    URLSession.shared.dataTask(with: url) { data, _, error in
        guard let data, let image = NSImage(data: data) else {
            FileHandle.standardError.write("nowplaying: couldn't load the cover \(u): \(error?.localizedDescription ?? "not an image")\n".data(using: .utf8)!)
            return
        }
        onMain {
            guard artURL == u else { return }
            let w = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            artwork = w
            info[MPMediaItemPropertyArtwork] = w
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }.resume()
}
