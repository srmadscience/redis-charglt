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
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClusterClient;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ChargingDemoTransactions extends BaseChargingDemo {

    /**
     * @param args
     */
    public static void main(String[] args) {

        msg("Parameters:" + Arrays.toString(args));

        if (args.length != 5) {
            msg("Usage: hostnames recordcount tpms durationseconds queryseconds");
            System.exit(1);
        }

        // Comma delimited list of hosts...
        String hostlist = args[0];

        // How many users
        int userCount = Integer.parseInt(args[1]);

        // Target transactions per millisecond.
        int tpMs = Integer.parseInt(args[2]);

        // Runtime for TRANSACTIONS in seconds.
        int durationSeconds = Integer.parseInt(args[3]);

        // How often we do global queries...
        int globalQueryFreqSeconds = Integer.parseInt(args[4]);

        // Extra delay for testing really slow hardware
        int extraMs = getExtraMsIfSet();

        try {
            Set<HostAndPort> jedisClusterNodes = new HashSet<HostAndPort>();
            for (int i = 0; i < hostlist.length(); i++) {
                jedisClusterNodes.add(new HostAndPort(hostlist.split(",")[i], 7379));
            }

            RedisClusterClient mainClient = RedisClusterClient.builder().nodes(jedisClusterNodes).build();
            RedisClusterClient otherClient = RedisClusterClient.builder().nodes(jedisClusterNodes).build();
            clearUnfinishedTransactions(mainClient, userCount, new Gson());

            boolean ok = runTransactionBenchmark(userCount, tpMs, durationSeconds, globalQueryFreqSeconds, mainClient, otherClient, extraMs);

            msg("Closing connection...");
            mainClient.close();
            otherClient.close();

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
