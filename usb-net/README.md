# usb-net

Mac의 인터넷을 USB(adb)로 크레마에 연결한다. Wi-Fi가 불안정할 때(휴대폰 핫스팟이 응답하지 않는 경우 등) 사용한다.

```
usb-net/start.sh      # Mac 암호 1회 입력, Ctrl-C로 종료
```

## 동작

| 단계 | 내용 |
|---|---|
| Mac | IP 포워딩 켜기, pf NAT 규칙(`com.apple/250.CremaUsbNat` 앵커, 10.77.0.2 → 기본 인터페이스) |
| 연결 | Mac `pppd pty "adb shell ..."` ↔ 기기 `su -c pppd /dev/tty`. 최신 adb에는 `adb ppp`가 없어 pty 옵션 사용 |
| 기기 | 기본 경로와 DNS(8.8.8.8, 1.1.1.1)를 `ppp0`으로 |
| 재연결 | `lcp-echo`로 끊김 감지(약 15초), 기기가 다시 잡히면 자동 재연결. sudo 인증은 백그라운드에서 유지 |
| 종료 | Ctrl-C: pppd 종료, 기기 경로·DNS를 Wi-Fi로 복원, NAT·포워딩 원복 |

- Android 4.2 앱은 Wi-Fi(또는 모바일)가 연결된 상태여야 온라인으로 판단하는 경우가 있다. 이때는 인터넷이 안 되는 Wi-Fi라도 연결해 둔다. 교보도서관은 Wi-Fi 없이도 동작했다.
- 기기 Wi-Fi가 다시 연결되면 Wi-Fi 기본 경로가 추가될 수 있다(두 경로가 공존).

## 관련 기기 설정

크레마 펌웨어는 5분 무입력 절전 후 약 30초 뒤 Wi-Fi 드라이버를 멈추고, 깨어나도 다시 켜지 않는다. 절전 중에도 Wi-Fi를 유지하도록 설정한다.

```
adb shell su -c "settings put global wifi_sleep_policy 2"    # 원래 값 1
```
