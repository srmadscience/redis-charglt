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
import ie.rolfe.redischarglt.documents.ExtraUserData;
import redis.clients.jedis.JedisPooled;

import java.util.Arrays;
import java.util.Random;

public class CreateChargingDemoData extends BaseChargingDemo {


    /**
     * @param args
     */
    public static void main(String[] args) {

        Gson gson = new Gson();
        Random r = new Random();

        msg("Parameters:" + Arrays.toString(args));

        if (args.length != 4) {
            msg("Usage: hostnames recordcount tpms  maxinitialcredit  ");
            System.exit(1);
        }

        // Comma delimited list of hosts...
        String hostlist = args[0];
        String[] hosts = hostlist.split(",");

        // How many users
        int userCount = Integer.parseInt(args[1]);

        // Target transactions per millisecond.
        int tpMs = Integer.parseInt(args[2]);

        // How long our arbitrary JSON payload will be.
        int loblength = 120;
        final ExtraUserData ourJson = getExtraUserDataAsObject(loblength, gson, r);

        // Default credit users are 'born' with
        int initialCredit = Integer.parseInt(args[3]);


        try {


            try (JedisPooled jedisPool = new JedisPooled(hosts[0], REDIS_DEFAULT_PORT)) {
                msg(jedisPool.ping());
                upsertAllUsers(userCount, tpMs, ourJson, initialCredit, jedisPool, jedisPool);
            }
            msg("Closing connection...");

        } catch (Exception e) {
            msg(e.getMessage());
        }

    }

}
