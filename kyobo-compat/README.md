# kyobo-compat

교보도서관 1.2.0(연어파 수정판)을 CREMA-0640L(Android 4.2.2)에서 쓰기 위한 APK 재패키징. 원본 APK는 저장소에 포함하지 않는다.

```
kyobo-compat/build.sh ~/Downloads/KyoboLibrary_1.2.0_patched_by_salmonpa_260929.apk
adb install -r kyobo-compat/out/KyoboLibrary_1.2.0_crema.apk
```

- 서명 키: `~/.android/debug.keystore`. 같은 키로 다시 설치하면 로그인·다운로드한 책이 유지된다.
- 처음 설치 시 기존 교보 앱(다른 서명)은 삭제해야 한다.
- 함께 필요: [`kyobo-bridge`](../kyobo-bridge/) (통신), 루트 인증서 추가.

## 수정 내용

| 문제 (4.2) | 증상 | 수정 |
|---|---|---|
| v1 서명이 SHA-256 | `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | SHA-1 v1 + v2로 재서명 |
| 도서관 사이트 JS가 ES2015 (`=>`, `let`, 템플릿 문자열) | 스크립트 파일 전체가 실행되지 않아 배너·목록이 비고 버튼이 동작 안 함 | 도서관 웹뷰의 `.js`를 받아 ES5로 변환 (`Es5`) |
| HTML 안의 인라인 스크립트 | 같은 문법 오류 | 오류 발생 시점에 같은 출처 URL(`/__crema_es5__`)로 변환본을 받아 바로 뒤에 삽입 |
| `Array.find`, `Promise`, `new URL()`, `classList`, `performance.getEntriesByType` 등 없음 | TypeError | 폴리필 (`poly.js`) |
| CSS `transform`, `display:flex`, `calc()` 미지원 | 대출 후 다이얼로그 높이가 0, 레이어 위치 틀어짐 | `-webkit-` 접두어, `-webkit-box` 대체, 높이 `calc()` → `auto` (`CssFix`) |
| `StandardCharsets`, `Objects`, `StatFs.*Long`, `getExternalFilesDirs` (API 18/19) | 다운로드 중 앱 종료 | 호출 지점 63곳을 `Api17`의 대체 메서드로 변경 |
| EPUB 뷰어가 WebView UA에서 `Chrome/NN` 버전을 읽음 | 책 열 때 종료 | UA에 `Chrome/30.0.0.0` 추가 (뷰어 한정) |

## 서재 공유 (크레마 홈)

| 항목 | 내용 |
|---|---|
| 내보내기 | 앱 실행 중 백그라운드 스레드가 `databases/books.db` 변경을 감지해 `/sdcard/crema/kyobo_shelf.json`과 표지(`/sdcard/crema/covers/kyobo-<id>.jpg`)를 기록. 제목·저자·진행률·반납일만 포함, `login_record`는 읽지 않음 |
| 책 열기 | `kyobolibrarykel://crema-open/<id>` → 앱의 서재와 같은 경로로 뷰어 실행. 앱이 꺼져 있어도 동작 |

## 구성

| 파일 | 내용 |
|---|---|
| `build.sh` | baksmali → 패치 → smali, 원본 zip을 유지하고 `classes.dex`만 교체(원본 압축 방식 유지: raw `.wav`는 STORED여야 함), zipalign, 서명 |
| `src/crema/kyobo/CompatWebViewClient.java` | 도서관 WebViewClient(`ui.main.z$g`)의 부모 클래스로 삽입. `.js`/`.css` 가로채기, 메모리·디스크 캐시 |
| `src/crema/kyobo/Es5.java` | 토크나이저 기반 변환: 화살표 함수, 템플릿 문자열, `let`/`const` |
| `src/crema/kyobo/CssFix.java` | CSS 대체 규칙 |
| `src/crema/kyobo/Api17.java` | API 18/19 대체 |
| `src/crema/kyobo/Shelf.java` | 서재 내보내기, 딥링크 |
| `poly.js` | 폴리필 |
| `debug.js` | `/sdcard/kyobo_compat.debug` 파일이 있을 때만 삽입. z-index 900 이상 레이어와 오류를 콘솔(logcat `Web Console`)에 기록 |

변환 코드를 고칠 때: Android 4.2 정규식은 짝 없는 `}`를 거부한다. 기기의 `dalvikvm`으로 변환을 먼저 실행해 Mac 결과와 비교한 뒤 설치한다. `CompatWebViewClient.VERSION`을 올려 디스크 캐시를 무효화한다.

## 알려진 문제

- 도서관 페이지 일부 레이아웃(상단 메뉴 너비 등)은 `calc()` 너비를 대체하지 않아 좁게 표시
- 도서관 사이트 자동 로그인은 사이트(도서관) 설정으로 꺼져 있음. 아이디 저장만 동작
- 앱 시작 시 "업데이트" 안내가 표시됨. "계속사용" 선택(업데이트 누르면 재부팅 전까지 동작 안 한다는 보고 있음)
- 확인한 도서관: 인천대학교. 다른 도서관은 페이지 구성이 같은 elibrary-front면 동작할 가능성이 높음
