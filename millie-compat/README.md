# millie-compat

Android 4.2.2(API 17)에서 밀리의 서재 2.4.0.0(minSdk 19)을 실행하기 위한 시스템 패치와 설정 파일.

밀리 APK는 AppGuard로 보호되어 실제 코드가 실행 시 복호화된다. APK 수정은 하지 않고, 앱이 요구하는 Android 4.4 동작을 4.2 시스템에 추가한다.

## 변경 사항

### 설치·실행

| 항목 | 내용 | 위치 |
|---|---|---|
| SHA-256 APK 서명 검증 | `JarVerifier`, `JarUtils`에 SHA-256 추가 | core.diff |
| minSdk 19 설치 거부 | 패키지 파서 minSdk 검사 통과 | framework.diff `PackageManager` |
| `libloader.so` 크래시 | 백그라운드 스레드에서 `ActivityThread.currentActivityThread()`가 null 반환. 앱 uid(≥10000)에 한해 `sCurrentActivityThread` 반환. 시스템 uid는 기존 동작 유지 | `ActivityThread`, `Instrumentation` |

### API 18/19 역이식

minSdk 19 빌드는 SDK 버전 검사가 제거되어 있어 4.4 API 호출 시 바로 종료된다.

- 클래스: `java.util.Objects`, `android.util.ArrayMap`
- 메서드: `View.isLaidOut`, `View.isAttachedToWindow`, `View.cancelPendingInputEvents`, `Bitmap.getAllocationByteCount`, `StatFs.get*Long`, `ActivityManager.isLowRamDevice`, `HandlerThread.quitSafely`, `Drawable.setAutoMirrored`, `ObjectAnimator.setAutoCancel` 외
- `Bitmap.reconfigure`는 구현하지 않음. 빈 구현 시 Glide 비트맵 크기 계산이 깨진다. Glide 비트맵 풀은 `webcompat/*.setstatic`으로 비활성화.

관련 도구: `tools/api_gap_scan.py`, `tools/api_gap_filter.py`, `tools/api19_backport.py`.

### 저장소·DRM

| 항목 | 내용 | 위치 |
|---|---|---|
| 외장 저장소 쓰기 | `READ_EXTERNAL_STORAGE`에 sdcard_rw, media_rw gid 추가 | `--system` (platform.xml) |
| DRM 의존 라이브러리 로드 실패 | `libPocoFoundation.so`, `libPocoZip.so`, `libsqlcipher.so`를 `/system/lib`로 복사 | `--system` |

### 네트워크

| 항목 | 내용 | 위치 |
|---|---|---|
| TLS 1.2 | Java 기본 프로토콜에 TLSv1.1, TLSv1.2 추가 | core.diff `NativeCrypto` |
| AES-GCM | OpenSSL에 있는 GCM 암호 묶음을 Java 목록에 등록 | core.diff `NativeCrypto` |
| WebView(Chromium 네트워크 스택) TLS | 밀리 프로세스에 `libcompat_webtls.so` LD_PRELOAD | `native/webtls_shim`, `--wrap` |

### EPUB 뷰어

뷰어는 epub.js 페이지이며 앱이 `WebView.evaluateJavascript`(API 19)로 결과값을 받는다.

- `EvalBridge`: `addJavascriptInterface` 기반 `evaluateJavascript` 구현
- `WebCompat`: `/system/etc/webview_compat/<패키지>.*` 파일이 있는 앱에만 적용되는 호환 계층

| 파일 | 내용 |
|---|---|
| `.js` | 페이지 앞에 삽입하는 폴리필 (classList, requestAnimationFrame, Event 생성자 등) |
| `.sub` | 에셋 JS/CSS 찾아 바꾸기 |
| `.dedupe` | 탭 한 번에 페이지가 두 번 넘어가는 중복 호출 제거 |
| `.swlayer` | WebView 소프트웨어 레이어 |
| `.unleak` | 종료된 Activity를 참조하는 스레드 객체의 Context 교체 |
| `.warm` | 뷰어 생성 시 Kotlin 리플렉션 사전 로드 |
| `.setstatic` | 정적 필드 설정 (Glide 비트맵 풀 크기 0) |
| `.shownsignal` | 첫 화면 표시 시 래퍼 앱에 알림 |

`WebViewClassic`에 destroy 이후 접근 시 크래시 방지 코드 추가.

### PDF

| 항목 | 내용 |
|---|---|
| `ArrayMap` 미존재 | framework에 역이식 |
| `libc++_shared.so`의 `statvfs` 심볼 미존재 | `libcompat_statvfs.so` (syscall 구현) LD_PRELOAD |

### 성능

- `millie_wrap.sh`: `build.prop`의 `wrap.kr.co.millie.eink`로 밀리만 래퍼 실행. shim 로드, `-Xverify:none`
- `libcompat_props.so` + `millie_props`: 밀리 프로세스에만 다른 시스템 속성값(힙 크기)
- `ImageView` 가시성 처리 역이식: 숨겨진 GIF 애니메이션 정지

대부분의 변경은 앱 uid 또는 패키지 이름으로 범위를 제한해 시스템 앱 동작에 영향이 없도록 했다.

## 파일

```
patches/framework.diff   framework.jar smali 패치 (v38, 52개 클래스)
patches/core.diff        core.jar smali 패치
bridge/                  EvalBridge, WebCompat, ArrayMap, AppWarmup Java 소스
native/*_shim/           LD_PRELOAD shim 소스와 빌드된 .so
webcompat/               /system/etc/webview_compat/ 설정 파일
tools/                   분석·측정 스크립트
build_jar.sh             원본 jar + diff → 패치된 jar
build_native.sh          shim 빌드 (NDK 필요)
```

## 빌드

```
millie-compat/build_jar.sh stock-framework.jar millie-compat/patches/framework.diff framework.jar
```

- 입력은 패치 전 원본 jar. `install.sh`는 최초 실행 시 원본을 `/sdcard/millie-crema-backup/stock/`에 보관한다.
- framework 변경 후 첫 부팅은 전체 dexopt로 수 분 소요.

## De-odex

기본 펌웨어는 odex 상태다. 이 상태에서 framework 클래스에 메서드를 추가하면 다른 odex 파일의 vtable 인덱스가 어긋나 부트루프가 발생한다. 모든 jar/apk에 classes.dex를 포함시키고 odex를 삭제(de-odex)한 뒤 적용해야 한다. `build_jar.sh`의 "no classes.dex" 오류는 de-odex 전 펌웨어라는 뜻이다.

## 주의 사항

- `com.android.location.fused` 비활성화 시 부트루프.
- 밀리 SplashActivity는 여전히 진행되지 않는다. [`millie-launcher`](../millie-launcher/)가 이를 우회한다.
- `--hosts`(밀리 서버 IP 고정)는 IP가 바뀌므로 권장하지 않는다.
