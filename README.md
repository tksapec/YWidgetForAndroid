# YWidgetForAndroid

Androidのホーム画面にニュース・天気・雨予報を表示するウィジェットアプリです。

現在のリリースは **v1.2** (`versionCode = 3`) です。

## 主な機能

- Yahoo!ニュース RSS の表示
  - 主要
  - 国内
  - 国際
  - 経済
  - IT
  - 科学
  - スポーツ
- 表示件数・表示スタイル・更新間隔の設定
- Open-Meteoを使用した現在の天気・気温表示
- 天気位置の指定
  - 現在地
  - 固定地域
  - 天気表示なし
- Yahoo! JAPAN Weather APIを使用した雨予報
  - 明示的に有効化した場合のみ動作
  - 現在地／固定地域に対応
  - 周辺地点も含めて降雨を判定
- WorkManagerによるバックグラウンド更新
- 更新診断情報の保持
- ウィジェットからの手動更新

## 動作環境

- Android 8.0 (API 26) 以降
- Target SDK: 35
- インターネット接続
- 現在地を使用する場合は位置情報権限

## 技術構成

- Kotlin
- Jetpack Compose
- Jetpack Glance AppWidget
- AndroidX WorkManager
- AndroidX DataStore
- Google Play services Location
- Open-Meteo API
- Yahoo!ニュース RSS
- Yahoo! JAPAN Weather API

## ビルド

JDK 17とAndroid SDKを用意し、リポジトリのGradle Wrapperを使用します。

Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
.\gradlew.bat assembleRelease
```

release APK:

```text
app\build\outputs\apk\release\app-release.apk
```

現在の`release` variantは、内部・個人配布用としてAndroid標準のdebug signing configurationで署名します。R8による縮小・難読化は無効です。

詳細は [docs/RELEASE.md](docs/RELEASE.md) を参照してください。

## 実機へのインストール

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

署名が一致しないAPKから更新する場合は上書きできません。アンインストールするとアプリ設定と配置済みウィジェットが削除されるため、必要な場合だけ実施してください。

## Yahoo!雨予報

Yahoo!雨予報機能の実装・設定上の注意点は [docs/YAHOO_RAIN_API_SETUP.md](docs/YAHOO_RAIN_API_SETUP.md) を参照してください。

このリポジトリのv1.2ではYahoo Client IDをビルド設定に埋め込む構成です。署名用keystoreやパスワードなどの秘密情報はリポジトリへ追加しないでください。

## リリース履歴

変更内容は [CHANGELOG.md](CHANGELOG.md) を参照してください。

## 開発時の確認

最低限、変更後は次を確認します。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
.\gradlew.bat assembleRelease
```

実機では以下をスモークテストします。

- アプリ起動
- ウィジェット配置・再描画
- ニュース更新
- 天気更新
- 固定地域のYahoo!雨予報
- 現在地のYahoo!雨予報
- 手動更新時にクラッシュしないこと

## ライセンス

現時点ではこのリポジトリにライセンスファイルは含まれていません。
