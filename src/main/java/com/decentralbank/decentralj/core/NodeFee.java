package com.decentralbank.decentralj.core;

import org.bitcoinj.core.*;

public class NodeFee {

    private static String userAddress;
    private static String BuyAddress;
    private static String SellAddress;
    public static final Coin TX_FEE = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;

    private final NetworkParameters params;

    public NodeFee(NetworkParameters params) {
        this.params = params;
    }

    public Address getAddressForEscrowFee(String userAddress) throws AddressFormatException {

            return new Address(params, userAddress);

    }

    public Address getAddressForBuyOfferFee(String BuyAddress) {
        try {
            return new Address(params, BuyAddress);
        } catch (AddressFormatException e) {
            e.printStackTrace();
            return null;
        }
    }

    public Address getAddressSellOfferFee(String SellAddress) {
        try {
            return new Address(params, SellAddress);
        } catch (AddressFormatException e) {
            e.printStackTrace();
            return null;
        }
    }
}