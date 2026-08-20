# FPL Plugin (for paper 1.21.11).

## Features
### News - You can upload and share news.
### Stocks - You can use stock system.
### Punishment - Admins can punish to people doing something illegal.

## Commands
### News
- uploadnews : Make a news and upload it.
- deletenews : Delete the news.
- checknews : Check news.
### Stocks
- buy : Buy a stock.
- sell : Sell a stock.
- setstars: Modify company's stars. (Only For Admin, Stars affect to the price)
- checkstats: Check the company's info.
- makecompany : Make a new company.
- setstartmoney : Set default money(Only For Admin).
- setmoney : Set target player's money.
### Casino
- gamble : Gamble with your money.
- roulette : Roll a roulette.
### Punishments
- punish : Punish target player with 4 levels.
- unpunish : Lift target player's penalty.
- Server owner can make exception player at punishments.yml.

| Levels | Punishments                   |
|--------|-------------------------------|
| 1      | Deop                          |
| 2      | Deop and force adventure mode |
| 3      | Deop and force spectator mode |
| 4      | Ban until specific time       |

## And more
### 엔티티 & 블록 차단
- You can block entities or blocks which can be used by terror in building server.
- You can add entities or blocks at resources/block_entity.yml.