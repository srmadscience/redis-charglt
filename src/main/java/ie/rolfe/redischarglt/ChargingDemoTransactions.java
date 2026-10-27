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

import redis.clients.jedis.JedisPooled;


import java.util.Arrays;

public class ChargingDemoTransactions extends BaseChargingDemo {

    /**
     * @param args
     */
    public static void main(String[] args) {

        msg("Parameters:" + Arrays.toString(args));

        if (args.length != 5 && args.length != 6) {
            msg("Usage: hostnames recordcount tpms durationseconds queryseconds [threads]");
            System.exit(1);
        }

        // Comma delimited list of hosts...
        String hostlist = args[0];
        String[] hosts = hostlist.split(",");

        // How many users
        int userCount = Integer.parseInt(args[1]);

        // Target transactions per millisecond.
        int tpMs = Integer.parseInt(args[2]);

        // How many threads. Defaults to the number of CPUs.
        int threadCount = getThreadCount(args, 5);

        // Runtime for TRANSACTIONS in seconds.
        int durationSeconds = Integer.parseInt(args[3]);

        // How often we do global queries...
        int globalQueryFreqSeconds = Integer.parseInt(args[4]);

        // Extra delay for testing really slow hardware
        int extraMs = getExtraMsIfSet();

        boolean ok = true;

        try {
            try (JedisPooled jedisPool = connect(hosts[0], threadCount)) {
                clearUnfinishedTransactions(jedisPool, userCount, threadCount);

                 ok = runTransactionBenchmark(userCount, tpMs, durationSeconds, globalQueryFreqSeconds, jedisPool, extraMs, threadCount);
            }
            msg("Closing connection...");


            if (ok) {
                System.exit(0);
            }

            msg(UNABLE_TO_MEET_REQUESTED_TPS);
            System.exit(1);

        } catch (Exception e) {
            msg(e.getMessage());
        }

    }

}
