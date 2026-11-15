# Needle Music Player — privacy

Needle is an independent open-source fork of Vinyl Music Player. Its source and
privacy questions are handled at
[the Needle repository](https://github.com/omblivion/VinylMusicPlayer/issues).
The original upstream maintainer is not the contact for this fork.

## Local files and settings

Needle reads your local music library with Android's audio permission. Settings,
library indexes and playback state are stored in the app's private storage.
Android backup may include app data according to your device's backup settings.
Exporting settings writes a backup to the location you select.

Tag editing and file deletion change the actual music files, which may also be
used by other apps. Image access is used for artwork. Notification permission is
optional for notifications outside the media-session exemptions.

## Network requests and external services

Artwork/metadata features can contact Deezer and Last.fm according to the
metadata-download setting. These requests may include artist or album names;
the receiving service also sees normal connection information such as your IP
address. The credits view contains remotely hosted contributor images. Opening
GitHub, external links or an image search contacts those sites.

Those services have their own privacy policies:
[Deezer](https://www.deezer.com/legal/personal-datas),
[Last.fm](https://www.last.fm/legal/privacy),
[GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

## Error reports

The optional error-report collection stores diagnostic information locally.
If you choose to submit a bug report, review the diagnostic text before sharing
it: it may contain device details, file paths or exception messages. Information
you submit to GitHub is subject to GitHub's policies and issue visibility.

## Changes

This description reflects the current fork and should be reviewed when network
features or distribution methods change. The original upstream policy is
archived separately in [docs/upstream-privacy.md](docs/upstream-privacy.md).
