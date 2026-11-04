# 가난한 자에게 전자책을

당근에서 3만원 이하면 구할 수 있는 이북리더기 크레마 사운드를 비교적 정상적으로 쓰기 위한 프로젝트입니다.

요즘 시대 개발자는 글을 잘 읽어야 하니까요!

써보고 싶으시면 [GUIDE.md](GUIDE.md) 참고해주시고, 코딩 에이전트에게 시키세요!

### 이게 왜 쩌는거냐면요?
- 밀리는 AppGuard가 있어서 앱을 조금만 수정해도 크래시가 나요. 그래서 안드로이드 프레임워크를 수정한답니다.
- 이 과정에서 네이티브 바이너리도 수정해보고, 별 난리를 다 쳐봤어요. 근데 결론은 루트 권한 따서 시스템 롬 을 뜯어고치는게 해결책이었어요.
- 옛날같았으면 시도도 못해볼 짓들을 코딩 에이전트 덕에 해볼 수 있었어요.

| 홈 | 서재 | 앱 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" width="240"> | <img src="docs/screenshots/library.png" width="240"> | <img src="docs/screenshots/apps.png" width="240"> |
| **설정** | **홈 앱 선택** | **실행 중인 앱 (홈 버튼 두 번)** |
| <img src="docs/screenshots/settings.png" width="240"> | <img src="docs/screenshots/home-apps.png" width="240"> | <img src="docs/screenshots/running-apps.png" width="240"> |

CREMA-0640L 실기기 화면 캡처(758×1024).

## 개요

| 항목 | 내용 |
|---|---|
| 대상 기기 | CREMA-0640L (Rockchip RK3026, RAM 512MB, 758×1024 전자잉크, Android 4.2.2 / API 17) |
| 대상 앱 | 밀리의 서재 `kr.co.millie.eink` 2.4.0.0 (minSdk 19) |
| 방식 | 밀리: APK는 수정하지 않고 기기 시스템(framework.jar, core.jar, 설정 파일)을 패치. 교보도서관: 사용자가 받은 APK를 재패키징 |

배경:
- 밀리 2.4.0.0은 Android 4.4(API 19) 이상을 요구한다. 4.2에서는 설치가 거부되고, 설치해도 실행 중에 4.4 API 호출로 종료된다.
- 밀리 서버는 TLS 1.2와 AES-GCM만 허용한다. Android 4.2 기본 설정은 TLS 1.0과 CBC 암호만 사용한다.
- 기본 홈(YES24)은 서비스가 종료되어 사용할 수 없다.

## 구성 요소

| 디렉터리 | 내용 |
|---|---|
| [`millie-compat/`](millie-compat/) | 시스템 패치. API 18/19 역이식, TLS 1.2·GCM, WebView `evaluateJavascript` 브리지, PDF·WebView용 LD_PRELOAD shim, 성능 설정 |
| [`millie-launcher/`](millie-launcher/) | 밀리 실행용 래퍼 앱. 멈추는 스플래시를 건너뛰고 LoginActivity를 직접 실행, 네트워크 대기, 종료된 프로세스의 태스크 정리 |
| [`crema-home/`](crema-home/) | 홈 런처. KOReader·교보도서관·밀리 서재와 이어 읽기, 프런트라이트·Wi-Fi 설정, 실행 중인 앱 관리 |
| [`kyobo-compat/`](kyobo-compat/) | 교보도서관 1.2.0 재패키징. 도서관 웹페이지 ES5·CSS 변환, API 18/19 대체, 서재 공유 |
| [`kyobo-bridge/`](kyobo-bridge/) | 교보 앱 전용 TLS 브리지 설정(제크리 사운드업 이미지의 바이너리 사용, 저장소 미포함) |
| [`usb-net/`](usb-net/) | Mac 인터넷을 USB(adb, PPP)로 크레마에 연결 |
| `install.sh` | 설치 스크립트. 항목 선택, 기기 백업과 복원 스크립트 생성 |
| `scripts/common.sh` | 공용 셸 함수 |

## 동작 범위

- 밀리: 로그인, 서재, 다운로드, EPUB·PDF 보기, 검색
- 교보도서관: 도서관 검색, 로그인, 책 검색, 대출, 다운로드, EPUB 보기 (인천대학교 도서관에서 확인)
- 크레마 홈에서 세 앱(KOReader, 교보, 밀리)의 책을 한 서재로 표시
- 밀리 APK 미수정, DRM·구독 처리는 앱의 기존 모듈이 그대로 수행
- 밀리의 로고·아이콘·코드는 저장소에 포함하지 않음. 런처 로고는 빌드 시 사용자 APK에서 추출(`--apk`), 크레마 홈은 설치된 앱의 아이콘을 실행 시 사용

측정값(크레마 홈): APK 약 1.36MB, 메모리 약 8MB(PSS), 콜드 스타트 약 0.43초.

## 요구 사항

- 루팅된 CREMA-0640L, USB 디버깅
- macOS 또는 Linux, JDK 8 이상, Android SDK(build-tools, platforms)
- 밀리의 서재 2.4.0.0 APK (선택: 로고 추출, 설치)

## 설치

```
./install.sh                                                    # 대화형
./install.sh --all --apk Millie-2.4.0.0.apk --install-millie
./install.sh --crema-home
./install.sh --dry-run ...                                      # 기기 변경 없이 생성될 스크립트만 출력
```

| 옵션 | 내용 | 디렉터리 |
|---|---|---|
| `--root` | su, SuperSU 설치 | — |
| `--framework` | 패치된 framework.jar | millie-compat |
| `--core` | 패치된 core.jar (TLS 1.2, GCM, SHA-256 서명 검증) | millie-compat |
| `--system` | platform.xml 저장소 권한, 밀리 DRM 네이티브 라이브러리 | millie-compat |
| `--wrap` | 밀리 전용 프로세스 래퍼와 shim | millie-compat |
| `--webcompat` | EPUB 뷰어 호환 설정 | millie-compat |
| `--browser-tls` | 기본 브라우저 TLS 1.2 | millie-compat |
| `--launcher` | 밀리 래퍼 앱 | millie-launcher |
| `--crema-home` | 홈 런처 | crema-home |
| `--debloat` | 미사용 YES24·CREMA 기본 앱 비활성화 | — |

## 복원

설치 시 기기에 `/sdcard/millie-crema-backup/<시각>/restore.sh`가 생성된다.

```
adb shell su -c "sh /sdcard/millie-crema-backup/<시각>/restore.sh" && adb reboot
```

`com.android.location.fused`를 비활성화하면 Android 4.2에서 system_server가 종료되어 부트루프가 발생한다. `--debloat` 목록에서 제외되어 있다.

## 알려진 문제

- 책을 연 직후 앱 내부 JSON 처리로 30~45초 지연 (앱 코드)
- PDF의 R/B 채널이 바뀌어 표시됨
- EPUB 사용 후 PDF를 열면 메모리 부족으로 프로세스가 종료되는 경우가 있음
- 휴대폰 핫스팟 사용 시 절전 해제 후 연결 상태로 표시되지만 데이터가 전달되지 않는 경우가 있음. 펌웨어가 절전 후 Wi-Fi 드라이버를 다시 켜지 않음(`wifi_sleep_policy 2`로 완화, [`usb-net`](usb-net/) 참고)

## 디렉터리 구조

```
install.sh
scripts/common.sh
millie-compat/
millie-launcher/
crema-home/            crema-home/shelf-sync/ (밀리 서재 복사)
kyobo-compat/
kyobo-bridge/
usb-net/
GUIDE.md
```

## Special Thanks

교보도서관과 인증서 문제는 네이버 [e북카페](https://cafe.naver.com/ebook) 회원분들이 먼저 길을 열어 주신 덕분에 해결할 수 있었습니다. 각 글의 파일은 이 저장소에 포함하지 않으며, 원 글에서 직접 받아야 합니다.

| 도움 받은 분 | 글 | 이 키트에서 쓴 부분 |
|---|---|---|
| 연어파 | [안드로이드4.4용 교보도서관 검색가능 패치앱](https://cafe.naver.com/ebook/958057) | 도서관 검색 API·웹방화벽을 고친 교보도서관 APK (`kyobo-compat`의 입력 파일) |
| 긴꿈 | [누글삼, 리페프용 (안드로이드4.4) - 교보전자도서관, 밀리의 서재 실행가능 수정 패치](https://cafe.naver.com/ebook/932724) | 4.4 기기에서 최신 인증·WebView를 브리지로 처리하는 방식 |
| 제크리(jecrits) | [크레마 사운드업 심폐소생기 (루팅 없이!!)](https://cafe.naver.com/ebook/946020) | 사운드업 시스템 이미지의 브리지 바이너리 (`kyobo-bridge`) |
| nerious2 | `KyoboLibrary_1.2.0_patched_by_nerious2` | 전자잉크용 교보도서관 1.2.0 원본 패치 |
| 불타는스님 | [크레마 사운드 알라딘 책다운, 리딩 등 정상화 방법](https://cafe.naver.com/ebook/933026) | 크레마 사운드 롬 백업·복구 절차 |
| Choryu | [구형 크레마 지원중단 해결패치 (인증서 지원 중단)](https://cafe.naver.com/ebook/845146) | 최신 루트 인증서로 교체하는 방식 |
| 헤겔티(hegelty) | [밀리의서재 Android 4.4용 APK 패치 방법](https://cafe.naver.com/ebook/966892), [millie-on-kitkat](https://github.com/hegelty/millie-on-kitkat) | 앱 내 Conscrypt 방식 등 구형 기기 밀리 실행 참고 |
| — | [교보이북 구버전 로그인 이슈에 대한 고찰](https://cafe.naver.com/ebook/637813) | 교보 로그인 WebView가 구형 안드로이드에서 막히는 원인 분석 |

글쓴이 이름은 카페 글 기준이며, 잘못된 부분이 있으면 알려 주세요.
