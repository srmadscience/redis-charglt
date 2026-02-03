/*
 * Copyright (C) 2026 David Rolfe
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */
/*
 * Copyright (C) 2025 Volt Active Data Inc.
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package ie.rolfe.redischarglt;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import ie.rolfe.redischarglt.documents.ExtraUserData;
import ie.rolfe.redischarglt.documents.UserTable;
import org.voltdb.voltutil.stats.SafeHistogramCache;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPooled;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Random;

/**
 * This is an abstract class that contains the actual logic of the demo code.
 */
public abstract class BaseChargingDemo {

    public static final long GENERIC_QUERY_USER_ID = 42;
    public static final long NO_SESSION = Long.MIN_VALUE;
    public static final Date NO_EXPIRY = new Date(0);

    public static final String REPORT_QUOTA_USAGE = "ReportQuotaUsage";
    public static final String KV_PUT = "KV_PUT";
    public static final String KV_GET = "KV_GET";
    public static final String DELETE_DOC = "Delete Doc";
    public static final String DELETE_DOC_ERROR = "Delete Doc Error";
    public static final String ADD_DOC = "Add Doc";
    public static final String ADD_DOC_ERROR = "Add Doc Error";
    public static final String UNABLE_TO_MEET_REQUESTED_TPS = "UNABLE_TO_MEET_REQUESTED_TPS";
    public static final String EXTRA_MS = "EXTRA_MS";
    protected static final int REDIS_DEFAULT_PORT = 6379;
    private static final String CHARGLT_DATABASE = "CHARGLT_DB";
    private static final String CHARGLT_USERS = "CHARGLT_USERS";
    private static final String ADD_CREDIT = "ADD_CREDIT";
    private static final String CLEAR_LOCK = "CLEAR_LOCK";
    private static final String CLEAR_UNFINISHED = "CLEAR_UNFINISHED";
    private static final String COUNT_USAGE_TOTAL_BY_DOC = "COUNT_USAGE_TOTAL_BY_DOC";
    public static SafeHistogramCache shc = SafeHistogramCache.getInstance();

    /**
     * Print a formatted message.
     *
     * @param message
     */
    public static void msg(String message) {

        SimpleDateFormat sdfDate;
        sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        String strDate = sdfDate.format(now);
        System.out.println(strDate + ":" + message);

    }


    /**
     * Convenience method to generate a JSON payload.
     *
     * @param length
     * @return
     */
    public static ExtraUserData getExtraUserDataAsObject(int length, Gson gson, Random r) {

        ExtraUserData eud = new ExtraUserData();

        eud.loyaltySchemeName = "HelperCard";
        eud.loyaltySchemeNumber = getNewLoyaltyCardNumber(r);

        StringBuffer ourText = new StringBuffer();

        for (int i = 0; i < length / 2; i++) {
            ourText.append(Integer.toHexString(r.nextInt(256)));
        }

        eud.mysteriousHexPayload = ourText.toString();

        return eud;
    }


    protected static void upsertAllUsers(int userCount, int tpMs, ExtraUserData ourEud, int initialCredit, JedisPooled redisClient, JedisPooled otherClient)
            throws InterruptedException {

        final long startMsUpsert = System.currentTimeMillis();

        SafeHistogramCache shc = SafeHistogramCache.getInstance();
        long currentMs = System.currentTimeMillis();
        int tpThisMs = 0;
        Random r = new Random();
        Gson g = new Gson();

        for (int i = 0; i < userCount; i++) {

            if (tpThisMs++ > tpMs) {

                while (currentMs == System.currentTimeMillis()) {
                    Thread.sleep(0, 50000);
                }

                currentMs = System.currentTimeMillis();
                tpThisMs = 0;
            }

            UserTable newUser = UserTable.getUserTable(ourEud, r.nextInt(initialCredit), i, startMsUpsert);
            newUser.addCredit(100, "Txn_" + i);
            newUser.reportQuotaUsage(100, 10, 100, "TX2_" + i);
            String jsonObject = g.toJson(newUser, UserTable.class);

            final long startMs = System.currentTimeMillis();

            redisClient.jsonSet(getKey(i), jsonObject);
            shc.reportLatency(BaseChargingDemo.ADD_DOC, startMs, "Add time", 2000);
            shc.incCounter(BaseChargingDemo.ADD_DOC);

            if (i % 100000 == 1) {
                msg("Upserted " + i + " users...");

                if (shc.getCounter(BaseChargingDemo.ADD_DOC_ERROR) > 0) {
                    msg("Errors detected. Halting...");
                    break;
                } else {
                    queryUserAndStats(g, redisClient, i, userCount);
                }

            }

        }


        long entriesPerMS = userCount / (System.currentTimeMillis() - startMsUpsert);
        msg("Upserted " + entriesPerMS + " users per ms...");
        msg(shc.toString());
    }

    private static String getKey(int i) {
        return CHARGLT_USERS + ":" + i;
    }


    protected static void deleteAllUsers(JedisPooled redisClient, int userCount, int tpMs) {

        final long startMsUpsert = System.currentTimeMillis();

        SafeHistogramCache shc = SafeHistogramCache.getInstance();
        long currentMs = System.currentTimeMillis();
        int tpThisMs = 0;


        for (int i = 0; i < userCount; i++) {

            if (tpThisMs++ > tpMs) {

                while (currentMs == System.currentTimeMillis()) {
                    try {
                        Thread.sleep(0, 50000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                currentMs = System.currentTimeMillis();
                tpThisMs = 0;
            }

            long startNs = System.currentTimeMillis();
            long howMany = redisClient.del(getKey(i));
            shc.reportLatency(BaseChargingDemo.DELETE_DOC, startNs, "Delete time", 2000);
            if (howMany == 1) {
                shc.incCounter(BaseChargingDemo.DELETE_DOC);
            } else {
                shc.incCounter(BaseChargingDemo.DELETE_DOC_ERROR);
            }

            if (i % 100000 == 1) {
                msg("Deleted " + i + " users...");
            }

        }

        long entriesPerMS = userCount / (System.currentTimeMillis() - startMsUpsert);
        msg("Deleted " + entriesPerMS + " users per ms...");
        msg(shc.toString());

    }

    /**
     * Convenience method to query a user a general stats and log the results
     */
    protected static void queryUserAndStats(Gson g, JedisPooled redisClusterClient, int queryUserId, int userCount) {

        // Query user #queryUserId...
        msg("Query user #" + queryUserId + "...");
        UserTable ut = getUser(g, queryUserId, redisClusterClient);
        reportDocument(ut);
        msg("Show amount of credit currently reserved for products...");
        getCurrentReservedCredit(redisClusterClient, userCount);

    }

    private static void getCurrentReservedCredit(JedisPooled theClient, int userCount) {

        final long getDocByDocMs = System.currentTimeMillis();

        SafeHistogramCache shc = SafeHistogramCache.getInstance();
        Random r = new Random();
        Gson g = new Gson();
        long total = 0;

        for (int i = 0; i < userCount; i++) {

            LinkedTreeMap userDoc = (LinkedTreeMap) theClient.jsonGet(getKey(i));
            UserTable ut = UserTable.fromLTM(g, userDoc);
            total += ut.getUsageBalance();

            if (i % 100000 == 1) {
                msg("Queried " + i + " users. Total is " + total);

            }

        }


        msg("Total for " + userCount + " users is " + total);

        shc.reportLatency(BaseChargingDemo.COUNT_USAGE_TOTAL_BY_DOC, getDocByDocMs, "Time to count usage", 10000);
    }

    private static UserTable getUser(Gson g, int queryUserId, JedisPooled theClient) {
        Object userDoc = theClient.jsonGet(getKey(queryUserId));
        UserTable ut = null;
        if (userDoc instanceof String) {
            ut = UserTable.fromJson(g, (String) userDoc);
        } else if (userDoc instanceof com.google.gson.internal.LinkedTreeMap) {
            ut = UserTable.fromLTM(g, (com.google.gson.internal.LinkedTreeMap) userDoc);
        }

        return ut;

    }


    static protected void reportDocument(UserTable ut) {
        if (ut == null) {
            msg("Document is null...");
        } else {
            msg(ut.toString());

        }
    }


    /**
     *
     * Run a key value store benchmark for userCount users at tpMs transactions per
     * millisecond and with deltaProportion records sending the entire record.
     *
     * @param userCount
     * @param tpMs
     * @param durationSeconds
     * @param globalQueryFreqSeconds
     * @param jsonsize
     * @param mainClient
     * @param deltaProportion
     * @param extraMs
     * @return true if >=90% of requested throughput was achieved.
     * @throws InterruptedException
     */
    protected static boolean runKVBenchmark(int userCount, int tpMs, int durationSeconds, int globalQueryFreqSeconds,
                                            int jsonsize, JedisPooled mainClient, int deltaProportion, int extraMs)
            throws InterruptedException {

        long lastGlobalQueryMs = 0;

        UserKVState[] userState = new UserKVState[userCount];

        Random r = new Random();
        Gson gson = new Gson();

        for (int i = 0; i < userCount; i++) {
            userState[i] = new UserKVState(i, shc);
        }

        final long startMsRun = System.currentTimeMillis();
        long currentMs = System.currentTimeMillis();
        int tpThisMs = 0;

        final long endtimeMs = System.currentTimeMillis() + (durationSeconds * 1000L);

        // How many transactions we've done...
        int tranCount = 0;
        int inFlightCount = 0;
        int lockCount = 0;
        int contestedLockCount = 0;
        int fullUpdate = 0;
        int deltaUpdate = 0;

        int firstSession = Integer.MIN_VALUE;

        while (endtimeMs > System.currentTimeMillis()) {

            if (tpThisMs++ > tpMs) {

                while (currentMs == System.currentTimeMillis()) {
                    Thread.sleep(0, 50000);

                }

                sleepExtraMSIfNeeded(extraMs);

                currentMs = System.currentTimeMillis();
                tpThisMs = 0;
            }

            // Find session to do a transaction for...
            int oursession = r.nextInt(userCount);

            if (firstSession == Integer.MIN_VALUE) {
                firstSession = oursession;
            }

            // See if session already has an active transaction and avoid
            // it if it does.
            if (userState[oursession].isTxInFlight()) {

                inFlightCount++;

            } else if (userState[oursession].getUserStatus() == UserKVState.STATUS_LOCKED_BY_SOMEONE_ELSE) {

                if (userState[oursession].getOtherLockTimeMs() + ReferenceData.LOCK_TIMEOUT_MS < System
                        .currentTimeMillis()) {

                    userState[oursession].startTran();
                    userState[oursession].setStatus(UserKVState.STATUS_TRYING_TO_LOCK);
                    GetAndLockUser(mainClient, userState[oursession], oursession, gson);
                    lockCount++;

                } else {
                    contestedLockCount++;
                }

            } else if (userState[oursession].getUserStatus() == UserKVState.STATUS_UNLOCKED) {

                userState[oursession].startTran();
                userState[oursession].setStatus(UserKVState.STATUS_TRYING_TO_LOCK);
                GetAndLockUser(mainClient, userState[oursession], oursession, gson);
                lockCount++;

            } else if (userState[oursession].getUserStatus() == UserKVState.STATUS_LOCKED) {

                userState[oursession].startTran();
                userState[oursession].setStatus(UserKVState.STATUS_UPDATING);

                if (deltaProportion > r.nextInt(101)) {
                    deltaUpdate++;
                    // Instead of sending entire JSON object across wire ask app to update loyalty
                    // number. For
                    // large values stored as JSON this can have a dramatic effect on network
                    // bandwidth
                    UpdateLockedUser(mainClient, userState[oursession],
                            userState[oursession].getLockId(), oursession, getNewLoyaltyCardNumber(r) + "",
                            ExtraUserData.NEW_LOYALTY_NUMBER, gson);
                } else {
                    fullUpdate++;
                    UpdateLockedUser(mainClient, userState[oursession],
                            userState[oursession].getLockId(), oursession, getExtraUserDataAsObject(jsonsize, gson, r), null, gson);
                }

            }

            tranCount++;
            userState[oursession].endTran(); //TODO - Fix when we make async

            if (tranCount % 100000 == 1) {
                msg("Transaction " + tranCount);
            }

            // See if we need to do global queries...
            if (lastGlobalQueryMs + (globalQueryFreqSeconds * 1000L) < System.currentTimeMillis()) {
                lastGlobalQueryMs = System.currentTimeMillis();

                queryUserAndStats(gson, mainClient, firstSession, userCount);

            }

        }


        msg(tranCount + " transactions done...");
        msg("All entries in queue, waiting for it to drain...");
        msg("Queue drained...");

        long transactionsPerMs = tranCount / (System.currentTimeMillis() - startMsRun);
        msg("processed " + transactionsPerMs + " entries per ms while doing transactions...");

        long lockFailCount = 0;
        for (int i = 0; i < userCount; i++) {
            lockFailCount += userState[i].getLockedBySomeoneElseCount();
        }

        msg(inFlightCount + " events where a tx was in flight were observed");
        msg(lockCount + " lock attempts");
        msg(contestedLockCount + " contested lock attempts");
        msg(lockFailCount + " lock attempt failures");
        msg(fullUpdate + " full updates");
        msg(deltaUpdate + " delta updates");

        double tps = tranCount;
        tps = tps / (System.currentTimeMillis() - startMsRun);
        tps = tps * 1000;

        reportRunLatencyStats(tpMs, tps);

        // Declare victory if we got >= 90% of requested TPS...
        return tps / (tpMs * 1000) > .9;
    }

    private static void GetAndLockUser(JedisPooled redisClusterClient, UserKVState userKVState, int sessionId, Gson gson) {

        final long startMs = System.currentTimeMillis();
        try {
            UserTable ut = redisClusterClient.jsonGet(getKey(sessionId), UserTable.class);
            if (ut == null) {
                msg("User not found");
            } else {
                ut.lock();
                redisClusterClient.jsonSet(getKey(sessionId), gson.toJson(ut));
                userKVState.setLockId(ut.userSoftLockSessionId);
            }

            shc.reportLatency(BaseChargingDemo.KV_GET, startMs, "KV Get time", 2000);
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    private static void UpdateLockedUser(JedisPooled redisClusterClient, UserKVState userKVState, long lockId, int sessionId, Object extraPayload, String deltaOperationName, Gson gson) {

        final long startMs = System.currentTimeMillis();

        try {
            UserTable ut = redisClusterClient.jsonGet(getKey(sessionId), UserTable.class);
            if (ut == null) {
                msg("User not found");
            } else {
                if (ut.isLockedBySomeoneElse(lockId)) {
                    msg("Locked by someone else");
                }
                if (deltaOperationName != null && deltaOperationName.equals(ExtraUserData.NEW_LOYALTY_NUMBER)) {
                    ut.userDataObject.loyaltySchemeNumber = Long.parseLong((String) extraPayload);
                } else {
                    ut.setUserDataObject((ExtraUserData) extraPayload);
                }

                redisClusterClient.jsonSet(getKey(sessionId), gson.toJson(ut));
                userKVState.setLockId(NO_SESSION);

            }

            shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    /**
     * Used when we need to really slow down below 1 tx per ms..
     *
     * @param extraMs an arbitrary extra delay.
     */
    private static void sleepExtraMSIfNeeded(int extraMs) {
        if (extraMs > 0) {
            try {
                Thread.sleep(extraMs);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * Convenience method to remove unneeded records storing old allotments of
     * credit.
     *
     */
    protected static void clearUnfinishedTransactions(JedisPooled redisClusterClient, int usercount, Gson g)
            throws Exception {

        msg("clearUnfinishedTransactions...");
        for (int id = 0; id < usercount; id++) {

            final long startMs = System.currentTimeMillis();

            try {
                UserTable ut = redisClusterClient.jsonGet(getKey(id), UserTable.class);
                if (ut == null) {
                    msg("User not found");
                } else {

                    if (ut.clearSessions() > 0) {
                        redisClusterClient.jsonSet(getKey(id), g.toJson(ut));
                    }


                }

                shc.reportLatency(BaseChargingDemo.CLEAR_UNFINISHED, startMs, "clear unfinished", 2000);
            } catch (
                    Exception e) {
                e.printStackTrace();
            }
        }

        msg("...done");

    }

    /**
     *
     * Convenience method to clear outstanding locks between runs
     *
     * @param redisClusterClient
     */
    protected static void unlockAllRecords(JedisPooled redisClusterClient, int usercount, Gson g) {

        msg("Clearing locked sessions from prior runs...");
        for (int id = 0; id < usercount; id++) {

            final long startMs = System.currentTimeMillis();

            try {
                UserTable ut = redisClusterClient.jsonGet(getKey(id), UserTable.class);
                if (ut == null) {
                    msg("User not found");
                } else {

                    if (ut.userSoftlockExpiry != NO_EXPIRY || ut.userSoftLockSessionId != NO_SESSION) {
                        ut.userSoftlockExpiry = NO_EXPIRY;
                        ut.userSoftLockSessionId = NO_SESSION;

                        redisClusterClient.jsonSet(getKey(id), g.toJson(ut));
                        shc.reportLatency(BaseChargingDemo.CLEAR_LOCK, startMs, "clear unfinished", 2000);
                    }

                }


            } catch (
                    Exception e) {
                e.printStackTrace();
            }
        }


        msg("...done");

    }


    /**
     *
     * Run a transaction benchmark for userCount users at tpMs per ms.
     *
     * @param userCount              number of users
     * @param tpMs                   transactions per milliseconds
     * @param durationSeconds
     * @param globalQueryFreqSeconds how often we check on global stats and a single
     *                               user
     * @param mainClient
     * @return true if within 90% of targeted TPS
     * @throws InterruptedException
     */
    protected static boolean runTransactionBenchmark(int userCount, int tpMs, int durationSeconds,
                                                     int globalQueryFreqSeconds, JedisPooled mainClient, JedisPooled otherClient, int extraMs)
            throws InterruptedException {

        Gson g = new Gson();

        // Used to track changes and be unique when we are running multiple threads
        final long pid = getPid();

        Random r = new Random();

        UserTransactionState[] users = new UserTransactionState[userCount];

        msg("Creating internal client records for " + users.length + " users");
        for (int i = 0; i < users.length; i++) {
            // We don't know a users credit till we've spoken to the server, so
            // we make an optimistic assumption...
            users[i] = new UserTransactionState(i, 2000);
        }

        final long startMsRun = System.currentTimeMillis();
        long currentMs = System.currentTimeMillis();
        int tpThisMs = 0;

        final long endtimeMs = System.currentTimeMillis() + (durationSeconds * 1000L);

        // How many transactions we've done...
        long tranCount = 0;
        long inFlightCount = 0;
        long addCreditCount = 0;
        long reportUsageCount = 0;
        long lastGlobalQueryMs = System.currentTimeMillis();

        msg("starting...");

        while (endtimeMs > System.currentTimeMillis()) {

            if (tpThisMs++ > tpMs) {

                while (currentMs == System.currentTimeMillis()) {
                    Thread.sleep(0, 50000);

                }

                sleepExtraMSIfNeeded(extraMs);

                currentMs = System.currentTimeMillis();
                tpThisMs = 0;
            }

            int randomuser = r.nextInt(userCount);

            if (users[randomuser].isTxInFlight()) {
                inFlightCount++;
            } else {

                users[randomuser].startTran();

                if (users[randomuser].spendableBalance < 1000) {

                    addCreditCount++;

                    final long extraCredit = r.nextInt(1000) + 1000;

                    final long startMs = System.currentTimeMillis();
                    addCredit(mainClient, randomuser, extraCredit, g);
                    shc.reportLatency(BaseChargingDemo.ADD_CREDIT, startMs, "ADD_CREDIT", 2000);
                    shc.incCounter(BaseChargingDemo.ADD_CREDIT);
                    users[randomuser].endTran();
                    users[randomuser].spendableBalance += extraCredit;


                } else {

                    reportUsageCount++;

                    int unitsUsed = (int) (users[randomuser].currentlyReserved * 0.9);
                    int unitsWanted = r.nextInt(100);
                    final long startMs = System.currentTimeMillis();
                    reportQuotaUsage(mainClient, randomuser, unitsUsed,
                            unitsWanted, users[randomuser].sessionId,
                            "ReportQuotaUsage_" + pid + "_" + reportUsageCount + "_" + System.currentTimeMillis(), g, users[randomuser]);
                    shc.reportLatency(BaseChargingDemo.REPORT_QUOTA_USAGE, startMs, "REPORT_QUOTA_USAGE", 2000);
                    shc.incCounter(BaseChargingDemo.REPORT_QUOTA_USAGE);
                    users[randomuser].endTran();

                }
            }

            if (tranCount++ % 100000 == 0) {
                msg("On transaction #" + tranCount);
            }

            // See if we need to do global queries...
            if (lastGlobalQueryMs + (globalQueryFreqSeconds * 1000L) < System.currentTimeMillis()) {
                lastGlobalQueryMs = System.currentTimeMillis();

                queryUserAndStats(g, mainClient, (int) GENERIC_QUERY_USER_ID, userCount);

            }

        }

        msg("finished adding transactions to queue");
        msg("Queue drained");

        long elapsedTimeMs = System.currentTimeMillis() - startMsRun;
        msg("Processed " + tranCount + " transactions in " + elapsedTimeMs + " milliseconds");

        double tps = tranCount;
        tps = tps / (elapsedTimeMs / 1000);

        msg("TPS = " + tps);

        msg("Add Credit calls = " + addCreditCount);
        msg("Report Usage calls = " + reportUsageCount);
        msg("Skipped because transaction was in flight = " + inFlightCount);

        reportRunLatencyStats(tpMs, tps);

        // Declare victory if we got >= 90% of requested TPS...
        return tps / (tpMs * 1000) > .9;
    }


    private static void addCredit(JedisPooled redisClusterClient, int sessionId, long extraCredit, Gson g) {

        final long startMs = System.currentTimeMillis();
        final String txnId = "AC" + startMs;

        try {
            UserTable ut = redisClusterClient.jsonGet(getKey(sessionId), UserTable.class);
            if (ut == null) {
                msg("User not found");
            } else {


                ut.addCredit(extraCredit, txnId);

                redisClusterClient.jsonSet(getKey(sessionId), g.toJson(ut));
                shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);

            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void reportQuotaUsage(JedisPooled redisClusterClient
            , int randomuser, int unitsUsed, int unitsWanted, long sessionId
            , String txnId, Gson gson, UserTransactionState userTransactionStateState) {


        final long startMs = System.currentTimeMillis();

        try {
            UserTable ut = redisClusterClient.jsonGet(getKey(randomuser), UserTable.class);
            if (ut == null) {
                msg("User not found");
            } else {

                ut.reportQuotaUsage(unitsUsed, unitsWanted, sessionId, txnId);
                redisClusterClient.jsonSet(getKey((int) sessionId), gson.toJson(ut));
                userTransactionStateState.endTran();
                shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);

            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Turn latency stats into a grepable string
     *
     * @param tpMs target transactions per millisecond
     * @param tps  observed TPS
     */
    private static void reportRunLatencyStats(int tpMs, double tps) {
        StringBuffer oneLineSummary = new StringBuffer("GREPABLE SUMMARY:");

        oneLineSummary.append(tpMs);
        oneLineSummary.append(':');

        oneLineSummary.append(tps);
        oneLineSummary.append(':');

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, REPORT_QUOTA_USAGE);

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, KV_PUT);

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, KV_GET);

        msg(oneLineSummary.toString());

        msg(shc.toString());
    }

    /**
     * Get Linux process ID - used for pseudo unique ids
     *
     * @return Linux process ID
     */
    private static long getPid() {
        return ProcessHandle.current().pid();
    }

    /**
     * Return a loyalty card number
     *
     * @param r instance of Random
     * @return a random loyalty card number between 0 and 1 million
     */
    private static long getNewLoyaltyCardNumber(Random r) {
        return System.currentTimeMillis() % 1000000;
    }

    /**
     * get EXTRA_MS env variable if set
     *
     * @return extraMs
     */
    public static int getExtraMsIfSet() {

        int extraMs = 0;

        String extraMsEnv = System.getenv(EXTRA_MS);

        if (extraMsEnv != null && !extraMsEnv.isEmpty()) {
            msg("EXTRA_MS is '" + extraMsEnv + "'");
            extraMs = Integer.parseInt(extraMsEnv);
        }

        return extraMs;
    }


    private static JedisPoolConfig buildPoolConfig() {
        final JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(128);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(Duration.ofSeconds(60).toMillis());
        poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(30).toMillis());
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        return poolConfig;
    }

}
