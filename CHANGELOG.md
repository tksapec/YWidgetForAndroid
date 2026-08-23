# Changelog

このファイルにはYWidgetForAndroidの主な変更点を記録します。

## [1.2] - 2026-08-23

### Added

- Yahoo! JAPAN Weather APIを利用した雨予報機能を追加。
- 雨予報の有効／無効設定を追加。
- 固定地域と現在地の両方で雨予報を取得できるようにした。
- 中心地点に加えて周辺地点も含めた降雨判定を追加。
- 雨予報の期限切れ処理と再描画処理を追加。
- 雨予報専用のWorkManager更新処理を追加。
- 更新状態・更新結果・各種エラーを確認できる診断情報を拡充。

### Changed

- Yahoo雨予報の位置情報処理を見直し、現在地モードでは権限と位置情報の鮮度を確認するよう強化。
- Yahoo雨予報のレスポンス検証、時刻検証、書き込み所有権、WorkManagerの競合処理を強化。
- 通常のニュース・天気更新処理の競合・更新状態処理を改善。
- Yahoo Client IDをアプリのビルド設定へ埋め込む構成に変更。
- 内部・個人配布向けの`release` build typeを追加。
- `release` APKをAndroid標準debug signing configurationで署名する構成にした。
- `release`ではR8による縮小・難読化を無効化。

### Fixed

- 雨予報設定変更中の古いWorker結果が新しい設定へ書き込まれる可能性を抑止。
- 現在地権限の取消後に古い位置情報を雨予報送信へ使用しないための保護を強化。
- Yahoo雨予報レスポンスの欠損・重複・古い観測時刻を正常値として扱わないよう修正。
- Kotlinコンパイル時の`RainAlertWriteGuard`可視性エラーを修正。

### Verified

- `testDebugUnitTest`による単体テスト。
- `lintDebug`および`lintRelease`によるLint確認。
- `assembleDebug`および`assembleRelease`によるビルド確認。
- Android 15実機でのdebug APK／release APKインストールと起動。
- 固定地域および現在地でのYahoo雨予報実通信。
- ニュース・天気・雨予報Workerの正常終了をlogcatで確認。

### Release configuration

- `versionCode = 3`
- `versionName = 1.2`
- `minSdk = 26`
- `targetSdk = 35`
- 内部配布用release APKはdebug keystoreで署名。

## Earlier versions

v1.1以前については、このCHANGELOG作成時点では正式な変更履歴を整理していません。必要になった場合はGit履歴から追記します。
