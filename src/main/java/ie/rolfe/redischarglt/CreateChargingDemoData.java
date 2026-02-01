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
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClusterClient;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

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

            Set<HostAndPort> jedisClusterNodes = new HashSet<HostAndPort>();
            for (int i = 0; i < hostlist.length(); i++) {
                jedisClusterNodes.add(new HostAndPort(hostlist.split(",")[i], 7379));
            }

            RedisClusterClient mainClient = RedisClusterClient.builder().nodes(jedisClusterNodes).build();
            RedisClusterClient otherClient = RedisClusterClient.builder().nodes(jedisClusterNodes).build();


            upsertAllUsers(userCount, tpMs, ourJson, initialCredit, mainClient, otherClient);

            msg("Closing connection...");
            mainClient.close();
            otherClient.close();

        } catch (Exception e) {
            msg(e.getMessage());
        }

    }

}
