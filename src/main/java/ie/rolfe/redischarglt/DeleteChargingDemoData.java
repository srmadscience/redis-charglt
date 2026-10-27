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


public class DeleteChargingDemoData extends BaseChargingDemo {

    /**
     * @param args
     */
    public static void main(String[] args) {

        msg("Parameters:" + Arrays.toString(args));

        if (args.length != 3 && args.length != 4) {
            msg("Usage: hostnames recordcount tpms [threads]");
            System.exit(1);
        }

        // Comma delimited list of hosts...
        String hostlist = args[0];
        String[] hosts = hostlist.split(",");

        // Target transactions per millisecond.
        int recordCount = Integer.parseInt(args[1]);

        // Target transactions per millisecond.
        int tpMs = Integer.parseInt(args[2]);

        // How many threads. Defaults to the number of CPUs.
        int threadCount = getThreadCount(args, 3);

        try {
            try {


                try (JedisPooled jedisPool = connect(hosts[0], threadCount)) {
                    deleteAllUsers(jedisPool, recordCount, tpMs, threadCount, getExtraMsIfSet());
                }
                msg("Closing connection...");


            } catch (Exception e) {
                msg(e.getMessage());
            }


        } catch (Exception e) {
            msg(e.getMessage());
        }

    }


}
