# イグニ DPC（Device Policy Controller）

完全管理端末（Device Owner）向けの最小 DPC です。QR プロビジョニングが終わると、**端末標準ホーム（Samsung One UI 等）**のまま、許可リスト外について次を適用します。設定・Play・Chrome・LINE・カメラは表示を維持します。

- **ユーザーアプリ**（非システム）: Device Owner として `PackageInstaller.uninstall` で**サイレントアンインストール**（容量を解放）
- **システムアプリ**: アンインストールせず `DevicePolicyManager.setApplicationHidden(true)` で**非表示のみ**

- パッケージ名: `app.igni.dpc`
- アプリ名: `イグニ`
- 管理者コンポーネント: `app.igni.dpc/.AdminReceiver`
- minSdk 26 / targetSdk 35

## 何をするか

プロビジョニング完了時（`GET_PROVISIONING_MODE` → 完全管理端末、続けて `ADMIN_POLICY_COMPLIANCE`）、Device Owner 有効化時、起動完了時に、同じポリシーを冪等に適用します。ユーザー操作は不要です。

**許可リスト（残す・消さない）**

- `com.android.settings`（設定）および OEM Settings パッケージ
- `com.android.vending`（Play ストア）
- カメラ（静的 OEM リスト + **動的検出**: `IMAGE_CAPTURE` / `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` ハンドラ、および packageName に `camera` を含む MAIN/LAUNCHER アプリ。適用時に必ず unhide）
- `com.android.chrome`（Chrome；安定版が無い場合のみ beta）
- `jp.naver.line.android`（LINE）
- この DPC 自身 `app.igni.dpc`（`AdminActivity` が LAUNCHER。`HomeActivity` は無効・HOME にしない）
- **非表示対象**: `com.google.android.googlequicksearchbox` など Google アプリ／検索／Assistant（Chrome の代替にしない）
- SystemUI、IME など端末動作に必要なパッケージ

**隠さない安全リスト（例）**

SystemUI、PackageInstaller、PermissionController、Google Play 開発者サービス、セットアップウィザード、Managed Provisioning、デフォルトランチャー、IME（キーボード）、WebView など。これらを隠したり消したりすると端末が操作不能になるため、対象外です。

Lock Task（キオスク）は **デフォルトオフ** です。有効にする場合は `app/build.gradle.kts` の `ENABLE_LOCK_TASK` を `true` にしてください。

## ホーム画面（v1.0.11: 標準ランチャー）

**Igni を HOME にしない**（Galaxy A23 等でドック／管理ホームに固定されて Chrome が使えなくなる問題の修正）。

- マニフェスト: `HomeActivity` は **無効**（`enabled=false`、HOME/DEFAULT フィルタなし）
- `AdminActivity` のみ `MAIN` + `LAUNCHER`（管理・再適用・更新用）
- `PolicyApplier.apply()` のたび: `clearPackagePersistentPreferredActivities(admin, packageName)` のみ。**`addPersistentPreferredActivity` は呼ばない**
- ホームは Samsung One UI / 端末標準ランチャー。許可リストにより設定・Play・Chrome・LINE・カメラ・イグニがランチャーに残る
- Chrome: 適用時に明示 unhide + http/https 既定ハンドラ候補。Google アプリは force-hide。lock-task 既定オフ。カスタムホームなし
- ホーム1ページ目の自動ピンはベストエフォート（多くの OEM では確認必須 → Admin に正直なステータス）
- LINE: `jp.naver.line.android` を許可リストに追加し、適用時に明示 unhide。未インストール時は適用後に Uptodown 最新（APK/XAPK）→ Play フォールバック（v1.0.13）

## カメラ保護（v1.0.4）

一部 OEM ではカメラのパッケージ名が静的リストに無く、hide ポリシー適用後にホームからカメラが消えることがありました。v1.0.4 では:

- `KeepPackages.detectCameraPackages()` が次を **動的** に keep 対象にします
  - `MediaStore.ACTION_IMAGE_CAPTURE` / `android.media.action.IMAGE_CAPTURE` / `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` を解決するパッケージ
  - MAIN/LAUNCHER があり packageName に `camera` を含むパッケージ（大文字小文字不問）
  - 既存の静的リスト（Sony / Xiaomi / Transsion / Sharp / FCNT / Kyocera 等を拡充）
- `PolicyApplier.apply()` のたびに検出したカメラを **明示的に unhide** し、`HiddenStore` からも除去
- 管理画面（`AdminActivity`）に検出カメラパッケージ一覧を表示

## アンインストール vs 非表示（v1.0.5）

`PolicyApplier.apply()` は許可リスト外のパッケージを次のように扱います。

| 種別 | 判定 | 動作 |
|---|---|---|
| ユーザーアプリ | `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` なし | **サイレントアンインストール**（`PackageInstaller.uninstall`、結果はログ） |
| システムアプリ | 上記フラグあり | **非表示のみ**（`setApplicationHidden(true)`）。起動可能なもののみ |

- 許可リスト・DPC 自身・IME・SystemUI 等（`KeepPackages.shouldKeep`）は絶対に消さない／隠さない
- アンインストールしたパッケージは `HiddenStore` から除去（もう無いため）。システム非表示は追跡を継続
- 「アプリ一覧を表示に戻す」は **隠したシステムアプリのみ**復元可能。アンインストール済みユーザーアプリは復元不可（Play 等から再インストール）

## 表示ポリシー（v1.0.2+ / 強化 v1.0.3 / ダーク強化 v1.0.6）

`PolicyApplier.apply()`（プロビジョニング完了・起動・再適用時）で次も冪等に適用します。個別パスの失敗はログのみで、適用全体は止めません。

- **ダークモード ON（強化 / Samsung One UI v1.0.9）**:
  - `UiModeManager.setNightMode(MODE_NIGHT_YES)` / API 30+ は `setNightModeActivated(true)`（反射）
  - `Settings.Secure.ui_night_mode = 2`
  - **Samsung One UI**: `Settings.System.display_night_theme = 1`（Galaxy A23 等）
  - 追加で安全な OEM 系キーを試行: `dark_theme` / `theme_mode` / `night_mode` / `ui_night_mode`（Secure・System・Global、1 または 2 = ON）
  - Device Owner 時は反射で `DevicePolicyManager.setSystemSetting` / `setSecureSetting`（`ui_night_mode`・`display_night_theme`）も試行
  - 書き込み後に `setApplicationNightMode`（あれば）と Samsung / night 系ブロードキャストをベストエフォート送信
  - 結果を永続化し、管理画面に **Android SDK・API 有無・適用結果・display_night_theme 読み戻し** を表示
  - **SDK &lt; 29**（Android 10 未満）ではシステム暗色テーマが無い場合がある旨を日本語で注記（Sharp AQUOS sense3 の Android 9 など）
- **画面オフ 30 分**:
  - `Settings.System.putInt(..., SCREEN_OFF_TIMEOUT, 1_800_000)` のあと **読み戻してログ**
  - 利用可能なら反射で `DevicePolicyManager.setSystemSetting(admin, SCREEN_OFF_TIMEOUT, "1800000")`（DO SystemApi）も試す
  - `setMaximumTimeToLock(30 min)` は補完として残す。一部 OEM ではキーガード／画面オフと干渉しうるため、**SCREEN_OFF_TIMEOUT が残ることを優先**
  - 管理画面（`AdminActivity`）に現在の `SCREEN_OFF_TIMEOUT`（ms）を表示し、再適用後に確認できる

## Samsung One UI ダークモード（v1.0.9）

Galaxy A23 など One UI では標準の `UiModeManager` / `ui_night_mode` だけではダークが効かないことがあります。v1.0.9 の `applyDarkMode()` は次を追加で試します（いずれもベストエフォート・例外は握りつぶし）。

1. 既存: `UiModeManager` + `Settings.Secure.ui_night_mode=2`
2. Samsung クラシック: `Settings.System.putInt(cr, "display_night_theme", 1)`
3. 既知の安全キー: `dark_theme` / `theme_mode` / `night_mode`（System/Secure、1 または 2 = ON）
4. 反射 `DPM.setSystemSetting` / `setSecureSetting` で `ui_night_mode` と `display_night_theme`
5. 書き込み後: `setApplicationNightMode`（あれば）＋ Samsung / night 系ブロードキャスト
6. 管理画面: 適用後の `display_night_theme` 読み戻しが 1 かどうかを表示

v1.0.8 のマナーモード＋音量 0、更新ボタン、アンインストールは維持しています。

## v1.0.27（機内モード切替・Admin UI整理）

- **Admin UI**: Chrome / TikTokライトのインストール行（ボタン＋Play）を削除。プリインストール Chrome の許可リスト保護は維持。TikTokライトも許可リストは残す
- **機内モード**: 管理画面に「機内モード」MaterialSwitch を追加（更新の下・LINEの上）。Device Owner として `Settings.Global.AIRPLANE_MODE_ON` を切替
  - 優先: 反射 `DevicePolicyManager.setGlobalSetting`
  - フォールバック: `Settings.Global.putInt` + `WRITE_SECURE_SETTINGS`
  - 成功後に `Intent.ACTION_AIRPLANE_MODE_CHANGED`（`state` extra）を `sendBroadcastAsUser`（失敗時は `sendBroadcast`）
  - 失敗時は短い日本語トースト。`onResume` でスイッチ状態を再読込
- 維持: 日本語ロケール / Asia/Tokyo、LEAVE_ALL、ホームピン無し、Play 自動オープン無し、LINE / アライブ横並び
- versionCode **28** / versionName **1.0.27**

## v1.0.26（システム言語を日本語に）

セットアップ中／適用後も端末が英語のまま残る問題への対策。

- **QR**: `PROVISIONING_LOCALE=ja_JP` + `PROVISIONING_TIME_ZONE=Asia/Tokyo` を easy / povo に追加（既存の `LEAVE_ALL_SYSTEM_APPS_ENABLED=true` は維持）
- **PolicyApplier**: DO 確認後にシステムロケール `ja_JP` とタイムゾーン `Asia/Tokyo` をベストエフォートで設定（再適用でも実行 → 既登録の英語端末も工場出荷リセットなしで修正）
  - DPM `setConfiguredLocales`（あれば）→ ActivityManager `updatePersistentConfiguration` / LocalePicker → `DPM.setTimeZone` / `AlarmManager.setTimeZone`
  - `persist.sys.locale` は root 無しでは不可のためスキップ
- Manifest: `CHANGE_CONFIGURATION` / `SET_TIME_ZONE`
- versionCode **27** / versionName **1.0.26**
- Chrome preserve・ホームピン無し・Admin UI（1.0.23–25）は維持。Play は開かない。

## v1.0.25（ホーム画面へのピン留め撤廃）

- **削除**: `HomeLayoutHelper` と `requestPinShortcut` によるホーム画面へのショートカット追加を完全撤去
- **PolicyApplier**: `applyBestEffortHomeLayout` 呼び出しなし。標準ホーム（Samsung One UI 等）のまま
- **Admin**: `homeLayoutStatus` は非表示のまま更新停止
- 維持: 標準ホーム、AdminActivity LAUNCHER（ドロワー可）、Chrome プリインストール保護（v1.0.24）、管理 UI（v1.0.23）、Play 自動オープンなし 等
- versionCode **26** / versionName **1.0.25**

## v1.0.24（プリインストール Chrome 保護）

- **QR**: `PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED=true` を easy/povo ペイロードに追加（プロビジョニング中のシステムアプリ無効化を防止）
- **PolicyApplier**: 非表示／アンインストールループの前に Chrome 系を `setUninstallBlocked` + unhide + enable。プリインストール検出を明示ログ
- **ChromeInstaller**: PackageManager 上に Chrome 系があれば Uptodown 再インストールしない（無効化済みでも enable+unhide のみ）
- **Trichrome**: `com.google.android.trichromelibrary*` を keep / 非アンインストール（Chrome 依存）
- versionCode **25** / versionName **1.0.24**

## v1.0.23（インストール行を横並び）

- 管理画面のインストール操作を縦積みから **1行横並び** に変更: `[アプリ名] [Play]`（アライブは `[アライブ] [開く]`）
- メインボタン height **48dp**（weight=1）、Play/開くは TextButton ~88dp
- ステータス TextView は行の下にコンパクト配置（小さめ top margin・1行）
- 再適用／更新／個人用に戻すも height ~48dp、セクション間の大きな marginTop を縮小
- v1.0.22 ルール維持: アプリ名のみラベル、オレンジ免責は非表示、ステータスは日本語短文のみ
- versionCode **24** / versionName **1.0.23**

## v1.0.22（管理画面 UI ポリッシュ）

- インストールボタン表記をアプリ名のみに短縮（「を入れる」削除）。Play は「Play」、アライブ起動は「開く」
- オレンジ色の免責・注意文（`lineDisclaimer` / `chromeDisclaimer` / `tiktokLiteDisclaimer` / `darkModeNote` / `lockTaskNote`）をすべて非表示
- インストール状態表示を日本語短文のみに（英語ステータスキーや長い詳細テールを出さない）
- versionCode **23** / versionName **1.0.22**

## v1.0.10（Galaxy A23 UX）

- **標準ホーム**: Igni HOME を完全撤廃。再適用で persistent preferred をクリアし、Samsung ランチャーに戻す
- **Chrome 利用可**: `com.android.chrome` を必ず unhide / 非アンインストール。lock-task なし
- **ダーク（Settings 反映）**: 優先で `Settings.System.display_night_theme=1` を書き込み、読み戻しが 1 であることを管理画面に表示。併せて UiModeManager / `ui_night_mode=2` / DPM 反射設定
- versionCode **11** / versionName **1.0.10**

## v1.0.11（LINE 保持）

- **LINE 利用可**: `jp.naver.line.android` を許可リストに追加し、適用時に明示 unhide。Play ストアからインストールした LINE は再適用後も保持
- **標準ホーム維持**: Igni HOME は使わず、ドック変更なし
- versionCode **12** / versionName **1.0.11**

## v1.0.12（LINE 自動インストール）

ポリシー適用後、LINE が未インストールならバックグラウンドでインストールを試みます（`apply()` は待たない）。

1. **サイレント**: `BuildConfig.LINE_APK_URL`（既定: `https://github.com/mikasahahappy0526-create/i/releases/download/1/line.apk`）から APK を HTTPS 取得し、Device Owner の `PackageInstaller`（`MODE_FULL_INSTALL`）でインストール
2. **フォールバック**: ダウンロード／インストール失敗（404・ネットワーク等）時は Play ストアの LINE ページを開く（`market://details?id=jp.naver.line.android`、失敗時は HTTPS）
3. 管理画面に「**LINEを入れる**」ボタンと直近ステータスを表示（手動で同じフロー）
4. 許可リスト＋明示 unhide（v1.0.11）はそのまま。標準ホーム・ダーク・音量・自己更新は変更なし

**注意**: このリポジトリ／タスクでは LINE APK を再配布しません。サイレントインストールには、短いミラー `mikasahahappy0526-create/i` の release `1` にユーザーが合法に用意した `line.apk` を置く必要があります。無い場合は常に Play が開きます。

- versionCode **13** / versionName **1.0.12**

## v1.0.13（Uptodown 最新 LINE / XAPK）

ポリシー適用後、LINE が未インストールなら **Uptodown** から最新版を解決してサイレントインストールします（`apply()` は待たない）。ユーザーは第三者リスクを了承済み。

1. **解決**: Uptodown Android eAPI（HMAC → Bearer）で `jp.naver.line.android` の最新 `fileID` を取得し、`dw.uptodown.com` CDN URL を都度解決（固定 URL は使わない）
2. **インストール**: `.apk` は単体、`.xapk` は zip 展開して base+splits を同一 PackageInstaller セッションで投入。OBB はベストエフォートで `Android/obb/jp.naver.line.android/` へコピー
3. **フォールバック**: 解決／DL／インストール失敗時は Play ストア（`market://details?id=jp.naver.line.android`）
4. 管理画面「**LINEを入れる**」は同じフロー。免責: **非公式配布（Uptodown）・改変リスクあり・Playより危険**
5. 許可リスト・標準ホーム・ダーク・音量・自己更新は維持

- versionCode **14** / versionName **1.0.13**

## v1.0.14（Chrome 絶対保護 + 更新ミラー）

**Critical:** ポリシー適用後に Chrome が消える／隠れる問題と、管理画面の更新取得が HTTP 500 で失敗する問題を修正。

### Chrome 絶対保護
1. **Hard deny uninstall**: `PolicyApplier` は `PackageInstaller.uninstall` の前に硬拒否。対象: `com.android.chrome` / `com.chrome.beta` / `com.chrome.dev` / `com.chrome.canary` / Play / Settings / LINE / DPC / `KeepPackages.shouldKeep == true`
2. 適用のたび Chrome 系を **強制 unhide**（LINE/Settings と同じ）し `setUninstallBlocked(true)`
3. Chrome 未インストールなら `ChromeInstaller`（Uptodown 最新 APK/XAPK → Play `market://details?id=com.android.chrome`）を非同期実行
4. 管理画面「**Chromeを入れる**」+ ステータス + Uptodown 免責（LINE と同文）

### 自己更新ダウンロード修正
1. APK 取得は安定ミラー優先: `https://github.com/mikasahappy0526-create/i/releases/download/1/d.apk`
2. GitHub API は User-Agent / `Accept: application/vnd.github+json`、302 を手動フォロー
3. API が 500/403/レート制限なら「ミラーから取得」として更新インストールを許可
4. エラー文言を HTTP コード付きで分かりやすく表示

LINE Uptodown フロー・標準ホーム・ダーク・音量は 1.0.13 のまま維持。

- versionCode **15** / versionName **1.0.14**

## v1.0.16（個人用に戻す / Device Owner 解除）

管理画面に危険操作ボタン **「個人用に戻す（Device Owner解除）」** を追加。確認ダイアログのうえ Device Owner を自己解除します。

1. 非表示にしたシステムアプリを `PolicyApplier.unhideAll()` で再表示
2. `clearPackagePersistentPreferredActivities` / lock-task クリア / 自パッケージの uninstall-blocked 解除
3. `DevicePolicyManager.clearDeviceOwnerApp(packageName)`（deprecated だが DO 自己解除 API）
4. 成功後、管理画面の Device Owner 状態は **いいえ**。イグニは通常アプリになり、以降ポリシーは適用されない
5. アンインストール済みユーザーアプリは復元不可（従来どおり）

Chrome / LINE / 更新 / 標準ホーム / ダーク / 音量ポリシーは変更なし。

- versionCode **17** / versionName **1.0.16**


## v1.0.19（Googleアプリ非表示 + Chrome既定 + Igniを許可リスト）

Sense3 向け: Google アプリが Chrome の代わりに出る問題を解消。標準ホームのまま。

- Google アプリ／検索／Assistant を **強制非表示**（可能ならアンインストール）。Chrome の代替にしない
- 許可リストに **`app.igni.dpc`（イグニ）** を明示。適用時に unhide + enable（`AdminActivity` LAUNCHER）
- Chrome: 適用／インストール後に DPM で http/https の persistent preferred を Chrome に（ベストエフォート）
- ホーム1ページ目へのピンは `ShortcutManager.requestPinShortcut` のベストエフォートのみ。**サイレント保証なし**（OEM確認UIが多い）。カスタム HOME／ドックは再導入しない
- 適用・起動・compliance から Play を自動で開かない（v1.0.18 維持）
- versionCode **20** / versionName **1.0.19**

## v1.0.18（セットアップ中に Play を開かない）


**Critical:** QR / Device Owner セットアップやポリシー再適用中に Google Play ログイン画面へ飛んでホーム到達を妨げる問題を修正。

1. **自動経路では絶対に Play を開かない**: `PolicyApplier.apply()` / BootReceiver / PackageMonitor / compliance / PackageInstaller ステータス受信機 / `ensure*Async` から `openPlayStore` を呼ばない
2. **LINE / Chrome 自動インストールはサイレントのみ**: Uptodown 取得 + `PackageInstaller`。失敗時はログとステータス prefs のみ
3. **管理画面**: 「LINEを入れる」「Chromeを入れる」はサイレント。明示操作の「Playで入れる」ボタンのみ Play を開く
4. **`ensureChromeInstalledPrompt` の Play-first**: apply から削除。Chrome は unhide 済みならそのまま、未導入ならサイレント試行のみ
5. 標準ホーム / DO 解除 / 更新ボタン1つ / ダーク / 音量0 / アンインストール許可リストは維持

- versionCode **19** / versionName **1.0.18**

## v1.0.17（更新ボタン統合）

管理画面の自己更新を **「最新版に更新」** のワンタップフローに統合しました。

1. GitHub API を確認し、失敗時は更新ミラーへフォールバック
2. 最新なら「最新です」と表示して終了
3. 更新があれば直ちにミラー `d.apk` をダウンロードし、`PackageInstaller` で自己更新
4. 確認・ダウンロード・インストールの失敗は日本語ステータスで表示

ポリシー再適用、LINE、Chrome、個人用に戻す、各種ステータス表示は維持します。

- versionCode **18** / versionName **1.0.17**

## v1.0.15（Chrome Play-first + hidden 判定修正）

**Critical:** v1.0.14 でも「ポリシーを再適用」後に Chrome が無く、Play が開かない端末があった問題を修正。

1. **`isChromeInstalled`**: パッケージ有無だけでは足りない。Device Owner 時は DPM `isApplicationHidden` を確認し、hidden なら `setApplicationHidden(false)` で unhide。成功して使える状態になってから true。disabled なら `setApplicationEnabledSetting(ENABLED)` を試行。
2. **Play-first**: Chrome 欠落時は Uptodown より先に **メインルーパーで Play ストア**（`NEW_TASK|CLEAR_TOP|RESET_TASK_IF_NEEDED` + `CATEGORY_BROWSABLE`、優先 `com.android.vending`）を開く。その後バックグラウンドで Uptodown サイレントを任意試行。
3. **`ensureChromeInstalledPrompt`**: `PolicyApplier.apply()` の強制 unhide 後、まだ未インストールならメイン Handler に Play を post（ユーザーが必ず見える経路）。
4. 管理画面「**Chromeを入れる**」: UI スレッドで先に `openPlayStore`、続けてバックグラウンド Uptodown。ステータスは prefs 表示。
5. Hard-deny uninstall（Chrome / Play / Settings / LINE 等）は維持。

- versionCode **16** / versionName **1.0.15**




## 音声ポリシー（v1.0.8）

`PolicyApplier.apply()` で次も冪等に適用します（失敗はログのみ・適用全体は止めません）。

- **マナー / サイレント ON**: `AudioManager.setRingerMode(RINGER_MODE_SILENT)`（音量すべて 0 を優先。一部 OEM で SILENT が拒否された場合は `VIBRATE` にフォールバックし、その後も各ストリームを 0 に強制）
- **全ストリーム音量 0**: `setStreamVolume(stream, 0, 0)` — `MUSIC` / `RING` / `NOTIFICATION` / `SYSTEM` / `ALARM` / `VOICE_CALL` / `DTMF` / `ACCESSIBILITY`（利用可能なもの）。例外はスキップ
- マニフェスト: `MODIFY_AUDIO_SETTINGS`、任意で `ACCESS_NOTIFICATION_POLICY`（DND の interruption filter を NONE/PRIORITY に試みる・ベストエフォート）
- 管理画面に着信モードとメディア／着信音量の短いステータス行を表示

## リリース APK のビルド


JDK 17 以上と Android SDK（compileSdk 35 / build-tools 35）が必要です。

```bash
# SDK の場所（例）
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# リポジトリ直下に local.properties を置いてもよい
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleRelease
```

成果物:

`app/build/outputs/apk/release/app-release.apk`

プロジェクト同梱のキーストア `keystore/igni-release.jks`（パスワードは `keystore.properties`）で署名します。ローカル／CI 用です。本番配布では専用のキーストアに差し替えてください。

キーストアを自分で作る場合:

```bash
keytool -genkeypair -v \
  -keystore keystore/igni-release.jks \
  -alias igni \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<password>' -keypass '<password>' \
  -dname "CN=Igni DPC, O=Igni, C=JP"
```

その後 `keystore.properties` のパスワードとエイリアスを合わせてください。

## 署名チェックサム（QR 用）

`android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` は、**APK の署名証明書（DER）の SHA-256** を URL-safe Base64（パディングなし）にしたものです。

同梱キーストア向けの値:

```
yOZEhRr9nIbif0_vEKh4PE1exHHPPUQeDOuyGnkuOwA
```

キーストアを差し替えたら `./scripts/signature-checksum.sh` で必ず作り直してください。

### apksigner を使う

```bash
# SHA-256 ダイジェスト（コロン付き hex）を取得
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# hex をバイナリに戻し、URL-safe Base64（パディングなし）へ
# xxd がある場合:
echo '<SHA-256 hex without colons>' | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='

# xxd が無い場合:
python3 -c 'import binascii,base64; print(base64.urlsafe_b64encode(binascii.unhexlify("<hex>")).decode().rstrip("="))'
```

`apksigner` は Android SDK の `build-tools/<version>/apksigner` にあります。

### openssl / keytool を使う（キーストアから直接）

```bash
keytool -exportcert -alias igni -keystore keystore/igni-release.jks \
  | openssl dgst -binary -sha256 \
  | openssl base64 -A | tr '+/' '-_' | tr -d '='
```

または:

```bash
./scripts/signature-checksum.sh
./scripts/signature-checksum.sh app/build/outputs/apk/release/app-release.apk
```

キーストアを変えたらチェックサムも必ず作り直してください。

## QR プロビジョニング（Android 10+）

1. 端末を **工場出荷状態にリセット** する。
2. 初期設定のようこそ画面で、同じ場所を **6 回タップ** して QR リーダーを出す。
3. Wi-Fi に接続したあと、次のような JSON を QR にしたものを読み取る。

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "app.igni.dpc/.AdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://example.com/igni-dpc.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "yOZEhRr9nIbif0_vEKh4PE1exHHPPUQeDOuyGnkuOwA",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_LOCALE": "ja_JP",
  "android.app.extra.PROVISIONING_TIME_ZONE": "Asia/Tokyo",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false
}
```

`PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED` を `true` にすると、プロビジョニング中にシステムアプリが無効化されず、この DPC が後から非表示制御します。`PROVISIONING_LOCALE` は公式形式 `xx_yy`（例: `ja_JP`）。`PROVISIONING_TIME_ZONE` は IANA（例: `Asia/Tokyo`）。

ダウンロード URL は、端末が取得できる HTTPS 上の APK に置き換えてください。

### この DPC が実装しているセットアップフック

| アクション | 動き |
|---|---|
| `android.app.action.GET_PROVISIONING_MODE` | 許可モードに含まれる場合のみ `PROVISIONING_MODE_FULLY_MANAGED_DEVICE` を返す。仕事用プロファイルは選ばない。 |
| `android.app.action.ADMIN_POLICY_COMPLIANCE` | 非表示ポリシーを適用し `RESULT_OK` で終了。セットアップウィザードが完了できる。 |

## 開発用: adb で Device Owner にする

未プロビジョニング（アカウント未追加）の端末:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner app.igni.dpc/.AdminReceiver
```

## 管理画面

ランチャーの「イグニ」アイコン（`AdminActivity`）から開くと:

- Device Owner の **はい / いいえ**
- **ポリシーを再適用** — 許可リスト外のユーザーアプリをアンインストールし、システム不要アプリを非表示（Igni HOME 解除・Chrome unhide・ダーク強化・タイムアウト再設定・マナー／音量0 含む）
- **アプリ一覧を表示に戻す** — この DPC が**非表示にしたシステムアプリのみ**再表示（アンインストール済みユーザーアプリは復元不可）
- **個人用に戻す（Device Owner解除）**（v1.0.16）— 確認後に非表示アプリを再表示し Device Owner を解除。イグニは通常アプリになる。アンインストール済みアプリは戻らない
- **最新版に更新**（v1.0.17+）— GitHub API を確認し、更新があればミラー `d.apk` を取得して同じ署名キーで Device Owner として自己更新（工場出荷リセット／QR 不要）
- **LINE / Chrome / TikTokライト / アライブ**（v1.0.23+）— 各インストールは `[アプリ名] [Play]`（アライブは `[アライブ] [開く]`）の1行横並び。ボタンはアプリ名のみ。ステータスは行下の日本語短文のみ。オレンジ色の免責・注意文は非表示
- 現在のバージョン（versionName / versionCode）と更新ステータス
- 現在の `SCREEN_OFF_TIMEOUT`（ms）
- **音声**: 着信モード（SILENT/VIBRATE/…）とメディア／着信音量
- **ダークモード**: SDK バージョン、API 有無、直近の適用結果
- 許可リストの表示

### アプリ内更新（v1.0.17）

1. 管理画面で「最新版に更新」→ `https://api.github.com/repos/mikasahahappy0526-create/igni-dpc/releases/latest`
2. タグ（例 `v1.0.17`）を SemVer 比較し、最新なら「最新です」で終了
3. 新しい場合、または API 失敗時はミラー `https://github.com/mikasahahappy0526-create/i/releases/download/1/d.apk` を直ちにダウンロード
4. `PackageInstaller` セッション（`MODE_FULL_INSTALL`）で自己インストール
5. 更新後は `MY_PACKAGE_REPLACED` / バージョン変更検知でポリシーを再適用（必要なら「ポリシーを再適用」でも可）

## プロジェクト構成

単一モジュール（`:app`）、Kotlin、Android Gradle Plugin。

```
app/src/main/java/app/igni/dpc/
  AdminReceiver.kt
  GetProvisioningModeActivity.kt
  PolicyComplianceActivity.kt
  HomeActivity.kt
  AdminActivity.kt
  InstallStatusReceiver.kt
  LineInstallStatusReceiver.kt
  policy/PolicyApplier.kt
  policy/KeepPackages.kt
  line/LineInstaller.kt
  chrome/ChromeInstaller.kt
  ChromeInstallStatusReceiver.kt
  update/AppUpdateChecker.kt
  update/AppSelfUpdater.kt
  update/SemVer.kt
```
