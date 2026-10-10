# Regular expenses slice (added 2026-09-12)

Recurring costs — subscriptions, bills, loan instalments — with tag-style categories, in the same
backend and SPA. Package: `com.stockmarketanalyzer.backend.expense` (`domain/`, `repository/`,
`service/`, `web/` + `web/dto/`); frontend `features/expenses/`. Per-user like `wealth` and
`dividend`, with one deliberate exception: a category row whose `owner_sub` is null is **global**.

## Decisions the user made (asked up front)

| Question | Choice |
|---|---|
| How the recurrence is entered | **A preset dropdown plus a Custom escape hatch.** Stored as `interval` + `period` either way — the iCalendar `RRULE` shape (`FREQ` + `INTERVAL`). "Quarterly" is 3 + MONTHLY, not a fifth period, so there is exactly one spelling of every recurrence. Rejected: always showing both fields (fortnightly costs two interactions, "1 monthly" reads oddly) and a D/W/M/Y button row (four abbreviations needing tooltips). |
| Start date | **Added, optional.** Not in the original sketch; without it "6 + monthly" has no idea *which* months. Nullable because "this is simply what I pay, don't ask me since when" is a real answer. |
| Categories per item | **Many — true tags**, via `expense_item_category`. "Netflix" can be both Subscription and Entertainment. |
| Totals | **Computed in the UI**, against a period the user picks (day/week/month/year). The API stores and returns nothing normalised. |

## Why normalisation is a UI concern

Turning "every 2 weeks" into a figure per month needs an average month, and the only honest average
is a **presentation choice** — so the stored row stays exactly what was typed, and
`expense-recurrence.ts` does the arithmetic next to the selector that decides the period. Same
instinct as `WealthEntry` storing no `currentValue`: a derived number in a row is a number that goes
stale.

- `PERIOD_DAYS` uses **365.2425** (the mean Gregorian year) and a twelfth of it for a month. That is
  what makes the conversions consistent with *each other*: a monthly cost is exactly twelve times
  smaller than the same cost stated yearly, so a budget does not change when a row is re-entered in a
  different period. With 30-day months it is out by 1.4%.
- **The same-period case short-circuits to plain division.** Going the long way round gives
  `9.999999999999998` for a monthly 10 shown per month — invisible after rounding, but a figure
  somebody checks against a bank statement should be exactly what they typed.
- **Totals are per currency, never converted.** Unlike wealth and dividends, which exist to answer
  "what is all of this worth together" and cannot without rates. A list of bills reads fine per
  currency, and running it through a rate that moves daily would make last month's total change when
  nothing did. A mixed list therefore shows more than one total.
- **Only *active* rows are totalled** — started, not expired. Inactive rows are still listed, greyed,
  and the footer says `{counted} of {total} rows counted`, so the exclusion is visible rather than
  silently applied.

## Global vs personal categories

One table, told apart by whether `owner_sub` is null.

| Question | Choice |
|---|---|
| Where the "only admins may create global" check lives | **In `ExpenseCategoryService`, not `SecurityConfig`.** `/api/v1/expenses/**` must be `authenticated()` for every method — an ordinary `USER` writes their own rows — so this cannot be a path rule. It is a property of the *payload*, and only the service can see it. `CurrentUser.isAdmin()` was added for it, reading `ROLE_ADMIN` off the authorities so it agrees with `hasRole("ADMIN")` by construction (and works under the `local` profile). |
| A personal tag whose name matches a global one | **409.** Two identical labels in one picker, one shared and one not, is a bug report waiting to happen. |
| Moving a row between the two kinds | **400.** Personal → global hands one person's label to everybody; global → personal strips a shared one from everybody. Both are delete-and-recreate decisions, not edits. |
| Deleting a tag that is in use | **409 unless `?force=true`**, which untags the items and keeps them. Same shape as the wealth tree's cascade delete. A tag is a label — losing one must never lose the expense it was on. |
| Attaching somebody else's category | **404, the same answer an unknown id gets.** `findAllVisible` compares the result *count* with the request instead of looking ids up one at a time: a 404 per id would be a way to discover what other people tag their expenses with. |

## CSV import

Two more endpoints per entity, inside the slice's existing security prefix, so **no security config
changed** beyond adding the prefix itself.

| | |
|---|---|
| `GET .../import/sample` | header row + examples, `text/csv`, `Content-Disposition: attachment` |
| `POST .../import` | `multipart/form-data`, field `file` (+ `global` for categories), returns the counts |

Item columns: `name, amount, unit, interval, period, startDate, expiryDate, categories`. **Only the
first three plus `period` are required** — `interval` defaults to 1, both dates may be blank, and
`categories` is a `|`-separated list in one cell (not a comma: that is the field separator, and the
delimiter is sniffed from the header). Category columns: just `category`.

- **A tag named in a file is created if it does not exist** — as a personal category, after a global
  one of that name has been looked for. That is the opposite of what the dividend import does with an
  unknown ticker, deliberately: an instrument is reference data a file cannot be trusted to invent,
  whereas a tag *is* the word in the cell. Refusing the row would mean creating twelve categories by
  hand before a file could be uploaded at all.
- **Whether a category file is global is asked once, in the dialog, not per row.** A `global` column
  would have to answer what a non-administrator's `true` means, and both readings are bad: failing
  those rows punishes them for a column they were never meant to fill in, and honouring it is a
  privilege escalation. One switch, on a screen that only shows it to administrators, has neither
  problem.
- **Duplicate = the name already exists**, case-insensitively, for items as well as categories. An
  expense item has no natural key, but unlike a dividend — which can legitimately be paid twice on one
  day for one amount — its *name* is its identity in a budget: there is one "Netflix". The
  consequence, accepted for now: **an import cannot update an existing row.** Changing an amount is
  an edit.
- `period` accepts the enum names plus `day/days/week/weeks/month/months/year/years/annual/annually`,
  case-insensitively. Anything else is a failed row, never a guess.
- `CsvImportEndpoint.importCsv` gained an optional `extras` parameter and `CsvImportDialog` an
  optional switch (`toggleField` / `toggleLabelKey` / `toggleHintKey`). Existing implementations with
  a one-parameter method still satisfy the interface, so the rates and dividends dialogs are
  unchanged.

## Schema

`0010-create-expense.yaml`. Three tables, and one piece of **raw SQL** that a Liquibase unique
constraint cannot express:

```sql
CREATE UNIQUE INDEX ux_expense_category_owner_name
    ON expense_category (owner_sub, lower(category)) NULLS NOT DISTINCT
```

- `lower(category)` — "Food" and "food" are one tag, and the service compares them that way, so the
  backstop has to as well or a race stores both.
- `NULLS NOT DISTINCT` — by default Postgres treats every NULL as different, so without it the global
  rows (`owner_sub IS NULL`) would not be constrained against each other **at all**, which is exactly
  the set that must not contain duplicates. Postgres 15+; the project is on 18.
- `expense_item` — `owner_sub` VARCHAR(64), `name` VARCHAR(128), `amount` NUMERIC(24,6), `unit`
  VARCHAR(16) (normalised upper-case by `Units`), `recurrence_interval` INTEGER DEFAULT 1,
  `recurrence_period` VARCHAR(16), `start_date` / `expiry_date` DATE **both nullable** — the nulls are
  open ends of an interval, not missing data. Index on `(owner_sub, name)`.
- `expense_item_category` — composite PK, FK to the item **CASCADE** (the links are the item's), FK to
  the category **RESTRICT** (a tag in use cannot vanish from under the items carrying it; the service
  answers that as the 409 above). Extra index on `expense_category_id` for the reverse direction.

## Behaviour worth remembering

- **The paged item search is two queries, on purpose.** Fetching the `categories` collection with an
  entity graph *and* `LIMIT` makes Hibernate page **in memory** — it reads every matching row and
  throws most away, silently, getting slower as the table grows (the HHH90003004 warning).
  `findWithCategoriesByIdIn` hydrates one page's tags in a second query instead: two queries whatever
  the page size, and no N+1 either.
- **The frontend does not page at all.** Both the totals and the grouped view need every matching row
  — a figure computed from one page would change as you paged, and a category straddling a page
  boundary would simply be wrong. `listAll` reads every page (`size=500`, four in flight), and the
  filter bar then filters in the browser, so there is no Apply button and no round trip per keystroke.
- **An item with two tags appears under both in the grouped view, and the subtotals overlap.** That is
  the honest rendering of a tag model; picking one tag per item would invent an answer the data does
  not contain. The un-grouped total sits above the table, counting each expense once, and a note says
  so. Tree keys are `"<groupKey>:<itemId>"` because an item id is not unique in that tree.
- **`ExpenseItemRequest.categoryIds` is the complete set, not a delta** — same reasoning as the admin
  screens' "set roles" call: the screen shows checkboxes and the answer is always the whole list.
- Both dates validated as a pair: `expiryDate` before `startDate` is a 400 (and a failed row on
  import), because it is a typo rather than a zero-length expense.
- `active` *is* computed server-side, unlike the normalised figures: it is a fact about two stored
  dates and today, and both ends deciding it the same way matters.
- An `ExpenseItem` deleted takes its link rows and no tags; a category deleted takes no items.

## Frontend notes

`/expenses/items` and `/expenses/categories`, behind `authGuard` (not `adminGuard` — the screens are
for everybody; the API refuses the one administrator action on its own). Menubar item "Expenses" with
the two children, matching how Wealth tracker and Dividends already work.

- **The global switch is hidden, not disabled**, for anyone without the role — on the category form
  and on the import dialog. The API refuses it regardless, so showing it would only advertise a door
  that does not open. Same call as the Administration menu item.
- The items page is the first to use `p-table` **and** `p-treetable` for the same rows, switched by a
  `p-selectbutton`; the grouped view is two levels so it reads like the wealth tracker's sub-items.
- `p-selectbutton`'s "All" scope option has the value `'all'`, not `null`: the component identifies
  the selected option by value, and a null value is indistinguishable from nothing being selected, so
  the button would never light up.
- A 409 on saving a category is toasted by the page itself — the global error interceptor passes 409
  through untouched (the wealth page turns its own into a prompt), so it would otherwise look like a
  click that did nothing. A 409 on *deleting* one does become a prompt, which retries with
  `force=true`.
- i18n: a new `expenses.*` namespace plus two `import.*` entries. **274 keys in each of en/hu, no
  drift.** Hungarian `periodUnit` uses the adverbial forms (`hetente`, `havonta`) so "7 hetente" and
  "Gyakoriság: 7 hetente" both read correctly, where English needs the plural nouns.

## API

`/api/v1/expenses/items` — list (`search`, `unit`, `period`, `categoryId`, `activeOnly`, paged by
name), get, POST, PUT, DELETE, `POST /import`, `GET /import/sample`.
`/api/v1/expenses/categories` — list (`search`, `global`), get, POST, PUT (rename), DELETE (`force`),
`POST /import` (`global`), `GET /import/sample`.

**`SecurityConfig` gained `/api/v1/expenses/**` → `authenticated()`**, above the admin-only
POST/PUT/PATCH/DELETE rules — first match wins, as with wealth and dividends.

## Build status (2026-09-12)

Checked as far as the two sandboxes allow. **Nothing has been run against Spring or a database.**

- **The normalisation maths was actually executed.** `expense-recurrence.ts` was compiled with the
  project's own `tsc` and 21 assertions run against the real module: the identity case, the
  yearly↔monthly round trip, fortnightly, quarterly, half-yearly, preset round-tripping (including
  "every 7 weeks" falling through to Custom), per-currency totals, the two-tag double count, and
  inactive rows being listed but not totalled. All pass. This is what found the
  `9.999999999999998` above.
- **The CSV samples were actually parsed**, by the project's real `CsvFile`/`CsvRow`/`CsvValues`
  (compiled with two-field stubs for `MultipartFile` and `@Schema`, as the 2026-09-08 check did): 21
  assertions covering both samples, a semicolon-delimited Hungarian file (comma decimals, `2026. 1.
  1.` dates, accented tags, the group-size rule on `1234.56`), blank dates staying null, `|` tags, a
  missing required column being refused whole, and an uninterpretable period failing one row. All
  pass.
- **The frontend type-checks clean**, templates included: `ngc -p tsconfig.app.json` with
  `strictTemplates`, and the coverage was verified by temporarily breaking a symbol in
  `expense-items.page.ts` and watching it fail. `styles.scss` still compiles with `sass`.
- **Backend: parsed with `javac 21`, no structural errors.** The only complaints are
  dependency-resolution ones (no Maven Central from either sandbox) and the expected Lombok artifact —
  `ExpenseItem::getId` cannot resolve without the annotation processor.
- Liquibase YAML parses, and the changeset's shape was read back (three `createTable`, the `sql`
  index, two FKs, three `dropTable` rollbacks).
- `ExpenseApiIntegrationTest` (Testcontainers, never run — no Docker) covers: tagging an item with a
  global and a personal category, cross-subject isolation, a `USER` refused a global category (403),
  case-insensitive duplicates and the global-shadowing 409, the in-use 409 then the forced untag with
  the item surviving, a borrowed category id 404ing, `expiryDate` before `startDate`, `activeOnly`,
  renaming a global category as admin but not as user, and the personal↔global 400.
- **Left in the gitignored build tree**, since `device_bash` cannot delete: `backend/build/i18n-patch/`
  (the script that inserted the i18n blocks) and `backend/build/parse-check/` (copies of
  `CurrentUser.java` and `SecurityConfig.java`, staged to parse them — the real files are too deeply
  nested for the device bridge to stage). `gradlew clean` removes both.

## Natural next steps

A **next-due date** per row, now that `startDate` exists — cheap to compute from the interval and the
period, and the obvious use for the field. Report **which** CSV rows failed (`CsvRowException`
already carries the line number, so it is a list on `CsvImportResult` and a table in the dialog, and
it would land for every importer at once). Import that can *update* an existing expense rather than
skipping it as a duplicate. A chart of expenses by category (`ngx-echarts` is already wired). Vitest
over `expense-recurrence.ts`, which is pure and already has the assertions above written for it. And
the two architecture docs still describe the pre-expense package and API lists — worth folding a
reference to this file into both.
