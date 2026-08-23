# 内部配布用リリース手順

YWidgetForAndroidをGoogle Playではなく、APKを直接配布する場合の手順です。

現在の対象バージョン:

- `versionCode = 3`
- `versionName = 1.2`
- `applicationId = com.tksapec.ywidget`

## 1. 前提

- Windows開発PC
- JDK 17
- Android SDK
- ADBを使用できること
- リポジトリのGradle Wrapperを使用すること

現在の`release` variantはAndroid標準のdebug signing configurationを使用します。

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")
    }
}
```

この構成は内部・個人配布用です。Google Play公開用の正式署名ではありません。

## 2. ソースを最新化

```powershell
git pull --ff-only origin main
```

必要に応じて状態を確認します。

```powershell
git status
git log -5 --oneline
```

## 3. 自動確認

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
.\gradlew.bat assembleRelease
```

すべて`BUILD SUCCESSFUL`で終了することを確認します。

## 4. 署名確認

```powershell
.\gradlew.bat signingReport
```

`Variant: release`に対して`Config: debug`が表示されることを確認します。

通常のdebug keystoreは次の場所にあります。

```text
%USERPROFILE%\.android\debug.keystore
```

### 重要

このアプリでは、このdebug keystoreが実質的に更新署名キーになります。同じ`applicationId`のAPKを今後上書き更新するには、同じ署名キーが必要です。

そのため、使用中の`debug.keystore`は安全な場所へバックアップしてください。

- keystoreをGitへコミットしない
- パスワードをGitへコミットしない
- 公開リポジトリへアップロードしない

## 5. APK確認

生成物:

```text
app\build\outputs\apk\release\app-release.apk
```

PowerShellで確認:

```powershell
Get-Item .\app\build\outputs\apk\release\app-release.apk |
    Format-List FullName,Length,LastWriteTime
```

配布時は必要に応じてコピーして名称を明確にします。

```powershell
Copy-Item `
  .\app\build\outputs\apk\release\app-release.apk `
  .\YWidget-v1.2.apk
```

## 6. 実機インストール

接続端末を確認します。

```powershell
adb devices -l
```

上書きインストール:

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

`Success`になることを確認します。

### 署名不一致の場合

`INSTALL_FAILED_UPDATE_INCOMPATIBLE`など署名不一致が出た場合、現在インストール済みAPKと新しいAPKの署名キーが異なります。

アンインストールすればインストール可能になりますが、アプリ設定と配置済みウィジェットが失われます。必要な場合だけ実施してください。

```powershell
adb uninstall com.tksapec.ywidget
adb install .\app\build\outputs\apk\release\app-release.apk
```

## 7. バージョン確認

```powershell
adb shell dumpsys package com.tksapec.ywidget |
    Select-String "versionCode|versionName"
```

v1.2では次を確認します。

```text
versionCode=3
versionName=1.2
```

## 8. 実機スモークテスト

最低限、以下を確認します。

- アプリが起動する
- ウィジェットを配置できる
- ウィジェットが再描画される
- ニュースが更新される
- 天気が更新される
- 固定地域でYahoo!雨予報が更新される
- 現在地でYahoo!雨予報が更新される
- 「今すぐ再取得」でクラッシュしない

Yahoo!雨予報の確認用logcat例:

```powershell
adb logcat -c
$appPid = (adb shell pidof com.tksapec.ywidget).Trim()
adb logcat --pid=$appPid -v time |
    Select-String "RainAlertWorker|Yahoo rain|WM-WorkerWrapper|YWidget|AndroidRuntime"
```

正常時は`RainAlertWorker`が開始し、Workerが`SUCCESS`で終了し、`lastRainAlertError=null`となることを確認します。

雨が検出されていない場合、`rainAlertLevel=None`は正常です。

## 9. バージョンアップ時

次のリリースでは少なくとも以下を更新します。

1. `app/build.gradle.kts`の`versionCode`
2. `app/build.gradle.kts`の`versionName`
3. `CHANGELOG.md`
4. 必要に応じてREADMEの現在バージョン表記
5. APKファイル名

`versionCode`は必ず以前より大きい整数にします。

## 10. このリリース方式の範囲

現在の方式には以下を含みません。

- Google Play公開
- Android App Bundle (AAB)配布
- 専用production keystore
- R8/ProGuardによる縮小・難読化
- GitHub Actionsによる自動ビルド・自動配布

これらが必要になった場合は、現在の内部配布用設定とは分けて設計します。
