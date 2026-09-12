# 언어 파일 사용법

## 언어 선택과 적용

- `/language`: 현재 언어와 선택 가능한 언어를 표시합니다. 채팅에서 언어를 클릭해 선택할 수 있습니다.
- `/language ko_kr`, `/language en_us`: 자신의 표시 언어를 즉시 변경합니다. `ko`, `en`, `ko-KR`, `en-US`도 사용할 수 있습니다.
- `/lang`, `/언어`: `/language`의 별칭입니다.
- `/language reload`: 언어 파일과 기본 언어 설정을 다시 읽습니다. `fpl.language.reload` 권한이 필요하며 기본값은 OP입니다. 콘솔에서도 실행할 수 있습니다.

플레이어별 선택은 UUID를 기준으로 `plugins/FPLPlugin/player_languages.yml`에 저장됩니다. 재접속하거나 서버를 재시작해도 유지됩니다.

언어를 선택하지 않은 플레이어와 콘솔은 `plugins/FPLPlugin/config.yml`의 기본 언어를 사용합니다.

```yaml
language:
  default: ko_kr
```

설정 변경 후 `/language reload`를 실행합니다. 선택했던 언어 파일이 없어지면 기본 언어를 사용합니다.

## 파일 위치

개발 시 기본 파일은 다음 위치에 있습니다.

- `src/main/resources/languages/ko_kr.yml`
- `src/main/resources/languages/en_us.yml`

서버에서는 JAR에 포함된 언어 파일을 처음 실행할 때 `plugins/FPLPlugin/languages/`로 복사합니다. 이미 있는 서버 파일은 덮어쓰지 않습니다. 운영 중 문구 수정은 서버의 언어 파일을 편집한 다음 `/language reload`로 적용합니다.

새 언어는 기존 파일을 복사해 `ja_jp.yml`처럼 저장하고 번역하면 됩니다. 파일명은 소문자 언어 코드이며, `fr.yml` 또는 `fr_fr.yml` 형태를 지원합니다(각 부분 2~8자, 뒤 부분은 숫자도 가능). `ko`와 `en`은 선택 명령어에서 각각 `ko_kr`, `en_us`의 별칭이므로 파일명으로는 `ko_kr.yml`, `en_us.yml`을 사용하세요.

- `src/main/resources/languages/`에 추가한 파일: 다음 빌드의 JAR에 포함되며 서버 실행 시 자동으로 복사됩니다.
- `plugins/FPLPlugin/languages/`에 직접 추가한 파일: `/language reload` 후 선택 목록에 나타납니다. 코드 수정이나 재빌드는 필요하지 않습니다.
- `language.name`은 선택 목록에 표시할 언어 이름입니다.

## 문구와 치환값

모든 파일은 동일한 키 구조를 사용합니다. 번역할 때 키 이름과 `{0}`, `{1}` 등의 치환값 번호를 유지하세요. 문장에 따라 치환값 순서를 바꾸거나 같은 값을 반복할 수 있습니다.

```yaml
language:
  name: "English"
news:
  uploaded: "§aNews uploaded! ID: §e{0}"
  notification: "§6[News] §e{0} §7- {1}"
```

`news.uploaded`의 `{0}`은 뉴스 ID, `news.notification`의 `{0}`과 `{1}`은 각각 제목과 작성자입니다. 각 기본 문구의 같은 번호가 같은 값을 의미합니다. `§a`, `§e` 등의 Minecraft 색상 코드를 사용할 수 있고, 줄바꿈은 큰따옴표 안의 `\n`으로 작성합니다.

치환값에 들어 있는 `{0}` 같은 사용자 입력은 다시 치환하지 않습니다. 뉴스 제목·본문, 회사명·설명, 노선 데이터, 룰렛 항목, 플레이어 이름과 엔티티 타입 식별자는 원문을 유지합니다. 이를 둘러싼 메시지와 표시 형식이 번역됩니다. 서버 로그와 명령어 자체·입력 별칭은 번역 대상이 아닙니다.

문구를 찾는 순서는 다음과 같습니다.

1. 선택 언어의 서버 파일
2. 선택 언어의 JAR 기본 파일
3. 설정된 기본 언어의 서버 파일
4. 설정된 기본 언어의 JAR 파일
5. JAR의 한국어 기본 파일

따라서 업데이트로 새 키가 추가돼도 기존 번역 파일을 덮어쓰지 않고 기본 문구를 사용할 수 있습니다. YAML 문법 오류가 있으면 리로드는 실패하고 이전에 읽은 문구를 계속 사용합니다. 오류 원인은 서버 로그에서 확인할 수 있습니다.

## 코드에서 메시지 추가

수신자를 전달해 메시지를 생성합니다. 전체 알림도 플레이어별로 생성해야 합니다.

```kotlin
sender.sendMessage(plugin.messages.text(sender, "news.uploaded", id))

Bukkit.getOnlinePlayers().forEach { player ->
    player.sendMessage(plugin.messages.text(player, "news.notification", title, author))
}
```

클릭·호버·타이틀에 사용하는 Adventure 컴포넌트는 `messageComponent(plugin.messages.text(...))`로 생성하면 번역 파일의 색상 코드를 적용할 수 있습니다.

`./gradlew languageChecks`는 언어 키, 치환값, 대체 문구, 플레이어별 문구, 선택 저장 및 컴포넌트를 검증합니다. `./gradlew check`와 `./gradlew build`에도 포함됩니다. 실제 서버에서 `/language en_us` 전환 후 도움말·알림·룰렛·처벌 메시지를 확인하면 전체 동작을 점검할 수 있습니다.
