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

        if (args.length != 3) {
            msg("Usage: hostnames recordcount tpms");
            System.exit(1);
        }

        // Comma delimited list of hosts...
        String hostlist = args[0];
        String[] hosts = hostlist.split(",");

        // Target transactions per millisecond.
        int recordCount = Integer.parseInt(args[1]);

        // Target transactions per millisecond.
        int tpMs = Integer.parseInt(args[2]);

        try {
            try {


                try (JedisPooled jedisPool = new JedisPooled(hosts[0],REDIS_DEFAULT_PORT)) {
                    deleteAllUsers(jedisPool, recordCount, tpMs);
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
