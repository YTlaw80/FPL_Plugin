# Features
1. News - Upload and share news.
2. Stocks - Use stock system.
3. Casino - Roll a roulette or gamble with one's money.
4. Punishment - Admins can punish to people doing something illegal.
5. Routes - Check or edit routes about bus, metro, etc.

# Commands
## News
- uploadnews : Make a news and upload it.
- deletenews : Delete the news.
- checknews : Check news.
## Stocks
- buy : Buy a stock.
- sell : Sell a stock.
- setstars: Modify company's stars. (Only For Admin, Stars affect to the price)
- checkstats: Check the company's info.
- makecompany : Make a new company.
- setstartmoney : Set default money(Only For Admin).
- setmoney : Set target player's money.
## Casino
- gamble : Gamble with your money.
- roulette : Roll a roulette.
## Punishments
- punish : Punish target player with 4 levels.
- unpunish : Lift target player's penalty.
- Server owner can make exception player at punishments.yml.
## Routes
- routes : Display info about routes in the file. Can search with station or line.
- Server owner can edit an infos with `resources/routes.json`.

| Levels | Punishments                   |
|--------|-------------------------------|
| 1      | Deop                          |
| 2      | Deop and force adventure mode |
| 3      | Deop and force spectator mode |
| 4      | Ban until specific time       |

# And more
## Blocking Entities and blocks
- You can block entities or blocks which can be used by terror in building server.
- You can add entities or blocks at resources/block_entity.yml.
## Clear Entity
- clearentity: kill all entities except in safe_entity.yml.
- checksafeentity: check entities in safe_entity.yml.
- addsafeentity: add an entity to safe_entity.yml.
- delsaffeentity: delete an entity from safe_entity.yml.
- Only OP can use this features, and it's good for making the server pleasant.
- Following entities are in safe_entity.yml basically:
  - armor stand
  - block display
  - item display
  - item frame
  - glowing item frame
  - player
  - painting
  - mannequin

# Display languages
- Use `/language` (alias `/lang`) to choose your display language; `/language en_us` selects English.
- Player choices persist across reconnects and restarts. Set `language.default` in `config.yml` for the server default.
- Edit `plugins/FPLPlugin/languages/*.yml`, then run `/language reload` (OP by default).