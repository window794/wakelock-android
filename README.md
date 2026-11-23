# WakeLock

Android 8.0（API 26）以降向けの単発目覚ましです。洗面所、キッチンの順に登録済みQRを読むと音が止まり、その10分後に「起きていますか？」と通知します。「起きています」を60秒以内に選ぶと、その朝の起床確認が完了します。

window794が「自分にはこれが便利だ」と思って作りましたが、あなたの布団に勝てるかは未検証です。

アラームを止めた後の二度寝を減らすために、寝床からの移動と、その後も起きていることの両方を確認する個人開発のアプリです。Kotlin、Jetpack Compose / Material 3、AlarmManager、CameraX、ML Kit、Preferences DataStoreを使用しています。

WakeLock is a personal Android project developed with the assistance of OpenAI Codex. Requirements, design decisions, and testing are reviewed by the project owner.

## ビルドとインストール

このフォルダがGradleプロジェクトのルートです。Android Studioでは **Open** で `wakelock-android` を選択します。VS CodeやPowerShellでも同じGradle Wrapperが使えます。

このPCで検出した環境はWindows、Android Studio付属のJBR 25.0.3、Android SDK Platform 37.0、Build Tools 36.0.0です。プロジェクトではGradle 9.6.0、Android Gradle Plugin 9.4.1、組み込みKotlinとComposeコンパイラー2.2.10、Javaバイトコード17を使います。minSdkは26、compileSdkとtargetSdkは37です。

1. 別のPCではAndroid StudioのSDK ManagerなどでAndroid SDK Platform 37、Build Tools 36.0.0、Platform-Toolsをインストールします。JDKはAndroid Studio付属のJBRが利用できます。
2. クローンした `wakelock-android` の親フォルダから以下を実行します。すでにリポジトリのルートにいる場合は2行目から実行してください。JBRやSDKを別の場所に入れた場合はパスを読み替えてください。初回ビルドには依存関係取得用のネットワーク接続が必要です。

```powershell
cd wakelock-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

デバッグAPKの出力先は `app\build\outputs\apk\debug\app-debug.apk` です。実機で開発者向けオプションとUSBデバッグを有効にし、PCの接続を端末で許可してインストールします。

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
.\gradlew.bat :app:installDebug
```

## 最初のアラームを設定する

1. 個人用のQRを2枚用意し、洗面所とキッチンにそれぞれ設置します。開発環境での保存先は `../qrcodes/bathroom.png` と `../qrcodes/kitchen.png` です。QRを自分で作成する場合は、地点ごとにPythonの `secrets.token_urlsafe(16)` で生成した値を使い、後述の形式で文字列全体をQRにします。初回登録時は別の画面に表示した画像でも読み取れます。
2. WakeLockを開き、「QRコード登録」で各地点を選び、カメラを許可して読み取ります。2地点とも「登録済み」になったことを確認します。読み取りは登録・実行時ともCameraXとAPKに同梱したML Kitモデルを使用し、機内モードで動作する設計です。[ML Kit公式ガイド](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
3. ホームの「権限の状態」で正確なアラーム・通知・ロック画面の全画面通知を確認します。アラーム音量は端末側で調整します。
4. 「アラームの日時を設定」で日付・時刻を選び、「保存してオンにする」を押します。ホームの次回日時を確認してください。「権限の許可待ち」の場合はOSの予約が保留されています。オフにすると日時を保持して予約を取り消します。
5. 鳴動したら画面または通知から読み取りを開き、洗面所 → キッチンの順に認証します。2地点目まで音と振動を継続します。カメラが使えない場合は「緊急停止」→「緊急停止する」で中止できます。直近の緊急停止日時はホームに残ります。
6. 10分後に「起きていますか？」が表示されると、端末のアラーム音量で音と振動が繰り返されます。通知を開き、60秒以内に「起きています」を選ぶと音が止まり、起床完了です。「まだ眠い」または無応答の場合は音が続き、2地点の認証からやり直します。完了画面には最初の鳴動、最後に成功した2地点認証、最終確認の時刻が表示されます。

## 状態と保存

```text
IDLE → ALARM_RINGING → AWAITING_BATHROOM_QR → AWAITING_KITCHEN_QR
     → AWAITING_RECHECK（10分）→ CHECKING（60秒）→ COMPLETED
CHECKING → ALARM_RINGING          まだ眠い・期限切れ
実行中の各段階 → ABORTED          確認付き緊急停止
```

`Protocol.kt` に状態遷移とQR検証をまとめ、`WakeStore.kt` が予定日時・有効フラグ・段階・期限・結果・地点別トークンをPreferences DataStoreにまとめて保存します。`WakeViewModel.kt` を介してComposeに反映します。カメラ映像は保存しません。トークンの入力形式は `wakelock:v1:<地点>:<URL-safe文字22個>` で、地点・順番・トークン全文を照合します。

アプリ専用領域に保存し、OSバックアップを無効化しています。コピーした同一のQRは同じ地点として認証されます。地点名は洗面所が `bathroom`、キッチンが `kitchen` です。個人用QRはリポジトリの外に保管し、リポジトリ内の `qrcodes/` もGitの対象から除外しています。登録済みトークンを差し替えるときは、実行中の起床確認を終えてから登録画面で変更します。

## Androidの権限と予約の動作

| 権限・機能 | 動作 |
| --- | --- |
| 正確なアラーム | Android 12以降は `SCHEDULE_EXACT_ALARM` と `canScheduleExactAlarms()` を確認。許可後のブロードキャストとアプリ再開時に保存済み状態から予約します。 |
| 通知 | Android 13以降は `POST_NOTIFICATIONS` を許可してください。アラーム鳴動・起床の再確認・鳴動できない時の通知の各チャネルは、OSの通知設定で個別に変更できます。 |
| 全画面通知 | `USE_FULL_SCREEN_INTENT` を宣言し、Android 14以降は `canUseFullScreenIntent()` を確認します。使用できない場合は高重要度の通知から画面を開けます。表示形式はOSとチャネル設定によります。 |
| カメラ | QR読み取り画面が表示されている間に背面カメラを利用します。拒否後はアプリ権限設定へ移動できます。 |
| 鳴動 | `mediaPlayback` 型のフォアグラウンドサービスが `USAGE_ALARM` で音源をループ再生し、端末が対応していれば振動します。サービス開始が制限された場合は代替通知を試みます。 |
| 再起動・スリープ | `RECEIVE_BOOT_COMPLETED` で再起動後の初回ロック解除後に予約を復元します。受信処理と再生に `WAKE_LOCK`、振動に `VIBRATE` を使います。 |

`WakeApp.kt` は初回・10分後・60秒後のうち、その時点で必要な1件を `AlarmManager.setAlarmClock()` で予約します。同一の明示的・変更不可のPendingIntentで上書きし、配信時に保存済み期限を再照合します。完了・中止時は予約と通知を取り消します。アプリがバックグラウンドでもOSが配信でき、Doze中の目覚まし用途に対応します。[正確なアラーム](https://developer.android.com/develop/background-work/services/alarms/schedule) ／ [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)

BroadcastReceiverは短い復元・配信処理を行い、ユーザーが予約したアラームの配信時に再生サービスを起動します。再起動ブロードキャストからは予約の復元を行います。Android 15以降では再起動受信時のメディア再生サービス直接起動が制限されます。[ブロードキャスト](https://developer.android.com/develop/background-work/background-tasks/broadcasts) ／ [バックグラウンド起動制限](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) ／ [サービス種別](https://developer.android.com/develop/background-work/services/fgs/service-types)

全画面表示や通知表示の可否は端末設定に従います。Android 17の音声制御にも配慮し、アラーム音量の強制変更は行いません。[通知権限](https://developer.android.com/develop/ui/views/notifications/notification-permission) ／ [全画面通知の制限](https://developer.android.com/about/versions/14/behavior-changes-14) ／ [Android 17の音声制御](https://developer.android.com/about/versions/17/changes/bg-audio)

## 開発環境での検証結果

- デバッグAPKのビルドと署名検査に成功しました。APK内のバーコードモデル、アプリID `jp.wakelock`、minSdk 26、targetSdk 37を確認しました。
- JVM単体テストは9件成功しました。QR形式・地点・トークン照合、認証順序、再確認開始時の鳴動、回答・中止・期限切れ後の音と予約の状態、日時変更、保存レコードの復元を検証しました。
- Android Lintはエラー0件、依存ライブラリの更新案内が7件です。
- 既存の2地点のテキストが対応形式であること、4つのQR関連ファイルがGitの除外対象であること、APKに元ファイルが含まれていないことを確認しました。

## 実機テスト

プロジェクト所有者の実機で、QR登録、正確なアラームの許可設定、予定時刻の鳴動、鳴動後のQR読み取り、10分後の起床再確認の表示を確認しました。再確認時のアラーム音、60秒無応答時の再鳴動、下表の各条件を組み合わせた動作は引き続き検証対象です。最初は日中に操作できる状態で、1〜2分後の単発アラームを使ってください。

| 条件 | 操作と期待結果 |
| --- | --- |
| 基本・予約変更 | 過去日時に警告が出ることを確認。1〜2分後を保存してアプリを開き直し、日時が同じことを確認。時刻変更後は新しい時刻に1回だけ鳴動し、オフなら鳴動しないことを別々に試す。 |
| 画面ロック・バックグラウンド | 端末ロック中と、他アプリの使用中で試し、鳴動・振動・通知から実行画面を開けることを確認。全画面表示許可のオン・オフでも比較する。 |
| プロセス終了 | 予約後、ホームへ戻り `adb shell am kill jp.wakelock` を実行。予約時刻に鳴動することを確認。QR認証後の10分待ちでも試す。 |
| サイレント・省電力 | アラーム音量を決め、通常・マナーモード・おやすみモード・省電力で試す。通知、音、振動が端末の設定どおりか確認する。 |
| 権限拒否と復帰 | 正確なアラームを拒否して日時を保存。許可待ちを確認し、許可後に予約時刻に鳴ることを確認。通知拒否・チャネル無効・カメラ拒否も個別に試す。 |
| 再起動 | 再起動の時間を考慮して数分先に予約し、再起動後に端末を一度ロック解除。アプリを開かずに鳴動することを確認。 |
| QR認証 | 機内モードで登録・照合。別地点、不正形式、同地点の別トークン、洗面所QRの連続読み取りを拒否することを確認。スキャン中の回転・ホーム移動後も同じ段階から再開する。 |
| 再確認 | キッチン成功で音が止まったら画面をロックする。10分後、通知と同時にアラーム音が鳴り、通知を開いても音が続き、「起きています」を押すと止まることを確認する。別々の試行で「まだ眠い」「60秒放置」からQR認証に戻ること、緊急停止で音が止まることも確認する。 |
| 緊急停止 | 鳴動中・カメラ拒否中・10分待ち・回答待ちで試す。「続ける」は実行を維持し、「緊急停止する」は音と再通知を取り消し、ホームに停止日時が残ることを確認する。 |

## 既知の制限

- 電源断中、再起動後の初回ロック解除前、設定画面からの強制停止中は通知できません。強制停止後は手動でアプリを開いてください。すでに期限を過ぎていた予約は、復元できた時点で処理します。
- 正確なアラーム権限の剥奪でOS予約が取り消されます。通知拒否・通知チャネルの無効化・メーカー独自の省電力設定により、再確認が見えない、鳴動や復帰が遅れる場合があります。ロック画面からカメラを使う際に端末のロック解除が必要なこともあります。
- 時刻と期限は端末時計の絶対時刻で保存します。タイムゾーン変更時は同一瞬間を新しい現地時刻で表示し、手動で時計を進める・戻すと再確認までの実時間も変わり得ます。
- 単発予約は1件です。直近の緊急停止と現在のセッション結果を保持します。曜日別繰り返し、クラウド保存、QR生成はMVPの対象外です。
