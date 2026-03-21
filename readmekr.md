# 1.21.11 페이퍼 서버 전용 FPL 플러그인.

## 기능
### 뉴스 - 뉴스를 제작하고 업로드 할 수 있습니다.
### 주식 - 회사의 별점을 정하고 그에 따라 주식의 가격이 변동됩니다. 

## 명령어
### 뉴스
- uploadnews : 뉴스의 제목과 내용을 정하고 업로드합니다.
- deletenews : 뉴스 목록에서 자신이 올린 뉴스를 제거합니다.
- checknews : 지금까지 나온 뉴스 목록을 확인합니다.
### 주식
- buy : 주식을 구매합니다. 
- sell : 주식을 판매합니다.
- setstars : 주식회사의 별점을 조정합니다.
- checkstats : 주식회사의 정보를 확인합니다.
- makecompany : 주식회사를 새로 생성합니다.
- setstartmoney : 기본 돈을 설정합니다.
- setmoney : 특정 플레이어의 돈을 설정합니다.

## 빌드
- 배포용 JAR: `./gradlew shadowJar` → `build/libs/FPLPlugin-1.0.0.jar`
- `./gradlew build` 를 실행해도 `shadowJar` 가 함께 실행됩니다.