# Mnemo — API & Wire Protocol

Mnemo speaks **RESP2** (REdis Serialization Protocol version 2). Any client that speaks RESP2 —
`redis-cli`, `Jedis`, `Lettuce`, `redis-benchmark` — interoperates with Mnemo unchanged. This
document defines the wire format and the Week 1 command contract in full; later commands are
listed in the roadmap table at the end.

---

## 1. RESP2 type system

Every value on the wire is prefixed by a single ASCII byte that identifies its type. All frames
terminate with `\r\n`.

| Prefix | Type | Wire form | Java model |
| --- | --- | --- | --- |
| `+` | Simple string | `+OK\r\n` | `SimpleString(String value)` |
| `-` | Error | `-ERR message\r\n` | `ErrorReply(String prefix, String message)` |
| `:` | Integer | `:42\r\n` | `IntegerReply(long value)` |
| `$` | Bulk string | `$3\r\nfoo\r\n` · null: `$-1\r\n` | `BulkString(byte[] data)` · `NullBulk` |
| `*` | Array | `*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n` · null: `*-1\r\n` | `ArrayReply(List<RespValue>)` |

**Client → server:** commands are always sent as an **array of bulk strings**. The first element
is the command name (case-insensitive); subsequent elements are arguments.

**Inline mode:** bare text followed by `\r\n` (e.g. `PING\r\n`) is also accepted for telnet and
manual debugging. The server normalises it into the array-of-bulk-strings representation before
dispatch.

---

## 2. Week 1 command specification

### `PING`

Health-check and round-trip latency probe. Always succeeds; no key access.

#### PING — Request forms

```text
*1\r\n$4\r\nPING\r\n
*2\r\n$4\r\nPING\r\n$<len>\r\n<message>\r\n
```

#### PING — Responses

| Request | Response |
| --- | --- |
| `PING` (no argument) | `+PONG\r\n` |
| `PING <message>` | `$<len>\r\n<message>\r\n` (bulk echo of the argument) |

#### PING — Errors

`PING` with more than one argument: `-ERR wrong number of arguments for 'ping' command\r\n`

---

### `SET`

Store a byte-string value under a key. Overwrites any existing value and type unconditionally.
Week 1 accepts exactly two arguments; options (`EX`, `PX`, `NX`, `XX`, `GET`, `KEEPTTL`) are
deferred to Week 3.

#### SET — Request

```text
*3\r\n
$3\r\nSET\r\n
$<klen>\r\n<key>\r\n
$<vlen>\r\n<value>\r\n
```

#### SET — Response

```text
+OK\r\n
```

#### SET — Errors

| Condition | Response |
| --- | --- |
| Argument count ≠ 2 | `-ERR wrong number of arguments for 'set' command\r\n` |
| Capacity exhausted (Week 3+) | `-OOM command not allowed when used memory > 'maxmemory'\r\n` |

---

### `GET`

Retrieve the value stored at a key. Returns a null bulk string when the key does not exist.
Returns `-WRONGTYPE` if the key holds a non-string type (Week 2+, once types exist).

#### GET — Request

```text
*2\r\n
$3\r\nGET\r\n
$<klen>\r\n<key>\r\n
```

#### GET — Responses

| Condition | Response |
| --- | --- |
| Key exists, string type | `$<vlen>\r\n<value>\r\n` |
| Key does not exist | `$-1\r\n` |

#### GET — Errors

| Condition | Response |
| --- | --- |
| Argument count ≠ 1 | `-ERR wrong number of arguments for 'get' command\r\n` |
| Key holds wrong type (Week 2+) | `-WRONGTYPE Operation against a key holding the wrong kind of value\r\n` |

---

### `DEL`

Delete one or more keys. Returns the count of keys that existed and were removed; keys that did
not exist are silently skipped and do not affect the count.

#### DEL — Request

```text
*<1+N>\r\n
$3\r\nDEL\r\n
$<k1len>\r\n<key1>\r\n
...
$<kNlen>\r\n<keyN>\r\n
```

#### DEL — Responses

| Condition | Response |
| --- | --- |
| N keys deleted | `:<N>\r\n` |
| No keys matched | `:0\r\n` |

#### DEL — Errors

| Condition | Response |
| --- | --- |
| Zero arguments | `-ERR wrong number of arguments for 'del' command\r\n` |

---

## 2b. Sorted-set commands (Week 2)

A key may hold a sorted set instead of a string. A key holds exactly one type: running a sorted-set
command on a string key (or `GET` on a sorted set) returns `-WRONGTYPE …`; `SET` overwrites any prior
type; `DEL`/`EXISTS` work across types. Members are ordered by `score` ascending, ties broken by
unsigned byte order on the member.

### `ZADD key score member [score member ...]`

Adds members, or updates the score of members that already exist. Reply: integer — the number of
members **newly added** (updated members are not counted). Flags (`NX`/`XX`/`GT`/`LT`/`CH`/`INCR`) are
a later week.

| Condition | Reply |
| --- | --- |
| Members added/updated | `:<new members>\r\n` |
| Key holds a string | `-WRONGTYPE Operation against a key holding the wrong kind of value\r\n` |
| A score isn't a valid float | `-ERR value is not a valid float\r\n` (nothing is applied) |
| Missing/odd score-member args | `-ERR wrong number of arguments for 'zadd' command\r\n` |

Scores accept `inf` / `+inf` / `-inf`; `nan` is rejected.

### `ZRANK key member`

Reply: integer — the member's **0-based** rank (lowest score = `0`), or a null bulk (`$-1`) if the
member or the key is absent. `-WRONGTYPE` if the key holds a string.

### `ZRANGE key start stop [WITHSCORES]`

Returns members whose **0-based** rank is in `[start, stop]`, ascending. Indices may be negative
(from the end: `-1` is the last member) and are clamped to the set's bounds; an empty or inverted
range yields an empty array. With `WITHSCORES`, each member is followed by its score as a bulk string
(integral scores print without a decimal point: `1.0` → `1`; infinities as `inf`/`-inf`). The
index form only — `BYSCORE`/`BYLEX`/`REV`/`LIMIT` are a later week. `-WRONGTYPE` if the key holds a
string.

---

## 2c. Integer counter commands (Week 2)

`INCR` / `DECR` / `INCRBY` / `DECRBY` treat a string key as a signed 64-bit integer. The value is
stored as its decimal string, so a counter round-trips through `GET`/`SET`. A missing key is treated
as `0`. Reply: integer — the value after the operation.

| Command | Effect |
| --- | --- |
| `INCR key` | value + 1 |
| `DECR key` | value − 1 |
| `INCRBY key delta` | value + delta |
| `DECRBY key delta` | value − delta |

| Condition | Reply |
| --- | --- |
| Success | `:<new value>\r\n` |
| Stored value isn't a 64-bit integer | `-ERR value is not an integer or out of range\r\n` |
| `delta` isn't a 64-bit integer | `-ERR value is not an integer or out of range\r\n` |
| Result would overflow `long` | `-ERR increment or decrement would overflow\r\n` |
| Key holds a sorted set | `-WRONGTYPE Operation against a key holding the wrong kind of value\r\n` |

On any error the stored value is left unchanged.

---

## 2d. Keyspace commands (Week 2)

| Command | Reply | Notes |
| --- | --- | --- |
| `TYPE key` | simple string | `string`, `zset`, or `none` (absent key). More types as they land. |
| `DBSIZE` | integer | Number of keys, across all value types. |
| `FLUSHDB` | `+OK` | Empties the keyspace. `ASYNC`/`SYNC` option is a later week. |
| `FLUSHALL` | `+OK` | Same as `FLUSHDB` today (single node, one logical DB); kept distinct for client compatibility. |

---

## 3. Error conventions

All error replies use the format `-<PREFIX> <human-readable message>\r\n`. Clients that
pattern-match on the prefix string can handle error classes generically.

| Prefix | Meaning | Introduced |
| --- | --- | --- |
| `ERR` | General syntax / arity error | Week 1 |
| `WRONGTYPE` | Key holds a different data type | Week 2 |
| `OOM` | Write rejected: logical capacity exceeded | Week 3 |

Standard arity error: `-ERR wrong number of arguments for '<cmd>' command`
Unknown command: `-ERR unknown command '<NAME>', with args beginning with: ...`

---

## 4. Command roadmap

| Command | Syntax | Reply type | Target |
| --- | --- | --- | --- |
| `PING` | `PING [msg]` | simple / bulk | **W1** |
| `SET` | `SET key value` | simple `+OK` | **W1** |
| `GET` | `GET key` | bulk / null | **W1** |
| `DEL` | `DEL key [key …]` | integer | **W1** |
| `ECHO` | `ECHO msg` | bulk | W1 |
| `EXISTS` | `EXISTS key [key …]` | integer | W1 |
| `COMMAND` | `COMMAND [DOCS\|COUNT]` | array stub | W1 |
| `INCR` / `DECR` | `INCR key` | integer | W2 |
| `TYPE` / `KEYS` / `DBSIZE` | — | simple / array / integer | W2 |
| `HSET` / `HGET` / `HGETALL` | hashes | varies | W2 |
| `LPUSH` / `RPUSH` / `LPOP` / `RPOP` / `LRANGE` | lists | varies | W2 |
| `ZADD` / `ZSCORE` / `ZRANK` / `ZRANGE` / `ZRANGEBYSCORE` | sorted sets | varies | W2 |
| `INFO` | `INFO [section]` | bulk text | W2 |
| `EXPIRE` / `TTL` / `PERSIST` / `SETEX` | TTL | integer | W3 |
| `SET` options | `EX` / `PX` / `NX` / `XX` / `GET` / `KEEPTTL` | varies | W3 |
| `CONFIG GET/SET` | `maxmemory`, `maxmemory-policy` | array | W3 |

---

## 5. `INFO` metrics schema (Week 2+)

`INFO` returns a Redis-compatible bulk string of `field:value` lines grouped by `# Section`
headers. Mnemo exposes only the metrics that back the performance and correctness story; there is
no external dashboard.

```text
# Server
mnemo_version:0.1.0
jdk_version:25
gc:ZGC-generational
uptime_seconds:<n>

# Clients
connected_clients:<n>

# Memory  (logical capacity — NOT a heap measurement; see architecture-spec.md §3)
maxmemory_mode:maxbytes
maxmemory:<bytes>
used_payload_bytes:<bytes>
used_keys:<n>
evicted_keys:<n>

# Keyspace
db0_keys:<n>
rehashing:<0|1>
dict_pool_size:<n>

# Stats
total_commands_processed:<n>
instantaneous_ops_per_sec:<n>
keyspace_hits:<n>
keyspace_misses:<n>

# Shards  (Week 4)
shard_count:<n>
```
