package com.reddcoin.core.coins;

/**
 * @author John L. Jegutanis
 */
public class PeercoinMain extends CoinType {
    private PeercoinMain() {
        id = "peercoin.main";

        addressHeader = 55;
        p2shHeader = 117;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 500;

        name = "Peercoin";
        symbol = "PPC";
        uriScheme = "peercoin"; // TODO verify, could be ppcoin?
        bip44Index = 6;
        unitExponent = 6;
        feePerKb = value(10000); // 0.01PPC, careful Peercoin has 1000000 units per coin
        minNonDust = value(1);
        softDustLimit = value(10000); // 0.01PPC, careful Peercoin has 1000000 units per coin
        softDustPolicy = SoftDustPolicy.AT_LEAST_BASE_FEE_IF_SOFT_DUST_TXO_PRESENT;
    }

    private static PeercoinMain instance = new PeercoinMain();
    public static synchronized PeercoinMain get() {
        return instance;
    }
}