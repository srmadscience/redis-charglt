# REDIS-charglt

REDIS implementation of [Charglt](https://github.com/srmadscience/voltdb-charglt), but on REDIS

It Has the basic functionality of charglt, but is missing stuff - see TODO

## How it uses REDIS

Each user is one RedisJSON document at `CHARGLT_USERS:<id>`.

* Every change to a user is a read-modify-write protected by `WATCH`/`MULTI`/`EXEC`. If the document changes
  between the read and the write, `EXEC` fails and we re-read and retry, so concurrent clients can't
  overwrite each other's changes.
* Dates are stored as epoch ms, so documents don't depend on the JVM's locale.

## Running

You need Java 21+, Maven, and REDIS with the RedisJSON module, e.g.

    docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest

Build with `mvn package`, then run, with `target/classes` and the dependencies on the classpath:

| Class | Parameters |
|---|---|
| `ie.rolfe.redischarglt.CreateChargingDemoData` | hostnames recordcount tpms maxinitialcredit [threads] |
| `ie.rolfe.redischarglt.ChargingDemoTransactions` | hostnames recordcount tpms durationseconds queryseconds [threads] |
| `ie.rolfe.redischarglt.ChargingDemoKVStore` | hostnames recordcount tpms durationseconds queryseconds jsonsize deltaProportion [threads] |
| `ie.rolfe.redischarglt.DeleteChargingDemoData` | hostnames recordcount tpms [threads] |

`threads` defaults to the number of CPUs. Each thread owns the users where `id % threads == threadId` and
gets an equal share of `tpms`. All threads share one pooled, thread safe `JedisPooled` client. You can't
have more threads than `tpms`.

Documents written by versions before epoch ms dates can't be read - delete and recreate the users.

## See Also:

* [aerospike-charglt](https://github.com/srmadscience/aerospike-charglt)
* [mongodb-charglt](https://github.com/srmadscience/mongodb-charglt)
* [s2-charglt](https://github.com/srmadscience/s2-charglt)
* [voltdb-charglt](https://github.com/srmadscience/voltdb-charglt)
* [oracle-charglt](https://github.com/srmadscience/oracle-charglt)
* [postgres-charglt](https://github.com/srmadscience/postgres-charglt)

## TODO

* Feed to downstream systems
* Running totals currently do a doc-by-doc scan instead of a single call.

## Status

While this is fine to play with, it's not a fair representation of REDIS at the moment.


