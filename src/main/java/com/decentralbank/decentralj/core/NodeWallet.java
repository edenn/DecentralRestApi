package com.decentralbank.decentralj.core;

import java.io.File;
import java.io.IOException;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.store.UnreadableWalletException;
import com.google.common.collect.ImmutableList;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.WalletEventListener;

import com.decentralbank.decentralj.core.listeners.AddressConfidenceListener;
import com.decentralbank.decentralj.core.listeners.BalanceListener;
import com.decentralbank.decentralj.core.listeners.TxConfidenceListener;
import com.decentralbank.decentralj.core.listeners.BlockchainDownloadListener;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.KeyChainGroup;

import javax.servlet.ServletConfig;

import java.io.File;
import java.io.Serializable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.bitcoinj.script.ScriptOpCodes.OP_RETURN;
import static java.net.InetAddress.*;

public class NodeWallet {
	
	private String amountToSend;
	private String recipient;
	private Wallet wallet;
    private final String WALLET_FILE = "DECENTRAL";
	private NetworkParameters netParams = NetworkParameters.prodNet();
	private File walletFile;
    private Map<Address, Account> NodeInfo = new HashMap<Address, Account>();
    private Map<BigInteger, Transaction> transactionMap = new HashMap<BigInteger, Transaction>();
    private KeyChainGroup keyChainGroup;
    private WalletAppKit walletKit;                                  //high level wallet wrapper
    private WalletEventListener walletEventListener;                 // wallet listener for events
    private final List<AddressConfidenceListener> addressConfidenceListeners = new CopyOnWriteArrayList<>();
    private final List<TxConfidenceListener> txConfidenceListeners = new CopyOnWriteArrayList<>();
    private final List<BalanceListener> balanceListeners = new CopyOnWriteArrayList<>();
    private final List<DownloadListener> downloadListener = new CopyOnWriteArrayList<>();
    private static NodeWallet instance = new NodeWallet();

    public static void main(String args[]) throws AddressFormatException, UnknownHostException, UnreadableWalletException {
        System.out.println("NodeWallet");
    	NetworkParameters np = NetworkParameters.testNet();
        NodeWallet wallet = new NodeWallet();
        DownloadListener downloadListener = new DownloadListener();
        wallet.start(downloadListener);
        wallet.addNewAddress();
        //wallet.createDepositAddress();

    }
    
    public NodeWallet() {
        wallet = new Wallet(netParams);
    }

    public static synchronized NodeWallet getInstance() {
        return instance;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }
     /******* Wallet ******/

    //start waller and download the blockchain
    public void start(org.bitcoinj.core.DownloadListener downloadListener) {

        // If seed is non-null it means we are restoring from backup.
        walletKit = new WalletAppKit(netParams, new File("."), WALLET_FILE) {
            @Override
            protected void onSetupCompleted() {
                // TODO: make user wait for 1 confirmation
                walletKit.wallet().allowSpendingUnconfirmedTransactions();
                if (netParams != RegTestParams.get())
                    walletKit.peerGroup().setMaxConnections(11);
                walletKit.peerGroup().setBloomFilterFalsePositiveRate(0.00001);
                initWallet();
            }
        };

        walletKit.setDownloadListener(downloadListener)
                .setBlockingStartup(false)
                .setUserAgent("Decentral", "0.1");


        walletKit.setAutoSave(true);
        walletKit.startAsync();
    }

    private void initWallet() {
        wallet = walletKit.wallet();

        walletEventListener = new WalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                notifyBalanceListeners();
            }

            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                notifyBalanceListeners();
            }

            @Override
            public void onReorganize(Wallet wallet) {

            }

            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                notifyConfidenceListeners(tx);
            }

            @Override
            public void onWalletChanged(Wallet wallet) {

            }

            @Override
            public void onScriptsAdded(Wallet wallet, List<Script> scripts) {

            }

            @Override
            public void onKeysAdded(List<ECKey> keys) {

            }
        };
        wallet.addEventListener(walletEventListener);

        //TODO: Store into File

    }

    /*******************************************************************************************************************
     ** Listeners **
    *******************************************************************************************************************/

    public AddressConfidenceListener addAddressConfidenceListener(AddressConfidenceListener listener) {
        addressConfidenceListeners.add(listener);
        return listener;
    }

    public void removeAddressConfidenceListener(AddressConfidenceListener listener) {
        addressConfidenceListeners.remove(listener);
    }

    public TxConfidenceListener addTxConfidenceListener(TxConfidenceListener listener) {
        txConfidenceListeners.add(listener);
        return listener;
    }

    public void removeTxConfidenceListener(TxConfidenceListener listener) {
        txConfidenceListeners.remove(listener);
    }

    public BalanceListener addBalanceListener(BalanceListener listener) {
        balanceListeners.add(listener);
        return listener;
    }

    public void removeBalanceListener(BalanceListener listener) {
        balanceListeners.remove(listener);
    }



    // TransactionConfidence
    public TransactionConfidence getConfidenceForAddress(Address address) {
        List<TransactionConfidence> transactionConfidenceList = new ArrayList<>();
        if (wallet != null) {
            Set<Transaction> transactions = wallet.getTransactions(true);
            if (transactions != null) {
                transactionConfidenceList.addAll(transactions.stream().map(tx ->
                        getTransactionConfidence(tx, address)).collect(Collectors.toList()));
            }
        }
        return getMostRecentConfidence(transactionConfidenceList);
    }

    public TransactionConfidence getConfidenceForTxId(String txId) {
        if (wallet != null) {
            Set<Transaction> transactions = wallet.getTransactions(true);
            for (Transaction tx : transactions) {
                if (tx.getHashAsString().equals(txId))
                    return tx.getConfidence();
            }
        }
        return null;
    }

        //notify all confidence listeners
    private void notifyConfidenceListeners(Transaction tx) {
        for (AddressConfidenceListener addressConfidenceListener : addressConfidenceListeners) {
            List<TransactionConfidence> transactionConfidenceList = new ArrayList<>();
            transactionConfidenceList.add(getTransactionConfidence(tx, addressConfidenceListener.getAddress()));

            TransactionConfidence transactionConfidence = getMostRecentConfidence(transactionConfidenceList);
            addressConfidenceListener.onTransactionConfidenceChanged(transactionConfidence);
        }

        txConfidenceListeners.stream().filter(txConfidenceListener -> tx.getHashAsString().equals
                (txConfidenceListener.getTxID())).forEach(txConfidenceListener -> txConfidenceListener
                .onTransactionConfidenceChanged(tx.getConfidence()));
    }

   //get transaction confidence
    private TransactionConfidence getTransactionConfidence(Transaction tx, Address address) {
        List<TransactionOutput> mergedOutputs = getOutputsWithConnectedOutputs(tx);
        List<TransactionConfidence> transactionConfidenceList = new ArrayList<>();

        mergedOutputs.stream().filter(e -> e.getScriptPubKey().isSentToAddress() ||
                e.getScriptPubKey().isSentToP2SH()).forEach(transactionOutput -> {
            Address outputAddress = transactionOutput.getScriptPubKey().getToAddress(netParams);
            if (address.equals(outputAddress)) {
                transactionConfidenceList.add(tx.getConfidence());
            }
        });
        return getMostRecentConfidence(transactionConfidenceList);
    }

    //get the list of all outputs in a a transactions
    private List<TransactionOutput> getOutputsWithConnectedOutputs(Transaction tx) {
        List<TransactionOutput> transactionOutputs = tx.getOutputs();
        List<TransactionOutput> connectedOutputs = new ArrayList<>();

        // add all connected outputs from any inputs as well
        List<TransactionInput> transactionInputs = tx.getInputs();
        for (TransactionInput transactionInput : transactionInputs) {
            TransactionOutput transactionOutput = transactionInput.getConnectedOutput();
            if (transactionOutput != null) {
                connectedOutputs.add(transactionOutput);
            }
        }

        List<TransactionOutput> mergedOutputs = new ArrayList<>();
        mergedOutputs.addAll(transactionOutputs);
        mergedOutputs.addAll(connectedOutputs);
        return mergedOutputs;
    }

    // get most recent confidence for a transaction
    private TransactionConfidence getMostRecentConfidence(List<TransactionConfidence> transactionConfidenceList) {
        TransactionConfidence transactionConfidence = null;
        for (TransactionConfidence confidence : transactionConfidenceList) {
            if (confidence != null) {
                if (transactionConfidence == null ||
                        confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.PENDING) ||
                        (confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.BUILDING) &&
                                transactionConfidence.getConfidenceType().equals(
                                        TransactionConfidence.ConfidenceType.BUILDING) &&
                                confidence.getDepthInBlocks() < transactionConfidence.getDepthInBlocks())) {
                    transactionConfidence = confidence;
                }
            }

        }
        return transactionConfidence;
    }

    /*******************************************************************************************************************
     **  Balance **
    *******************************************************************************************************************/

    public Coin getAddressBalance(Address address) {
        if(wallet != null) {
            return getBalance(wallet.calculateAllSpendCandidates(true), address);
        }else {
           return Coin.ZERO;
        }
    }

    private Coin getBalance(LinkedList<TransactionOutput> transactionOutputs, Address address) {
        Coin balance = Coin.ZERO;
        System.out.println("called balance");
        for (TransactionOutput transactionOutput : transactionOutputs) {
            if (transactionOutput.getScriptPubKey().isSentToAddress() || transactionOutput.getScriptPubKey()
                    .isSentToP2SH()) {
                System.out.println("getkey");
                Address addressOutput = transactionOutput.getScriptPubKey().getToAddress(netParams);
                System.out.println(addressOutput);
                if (addressOutput.equals(address)) {
                    System.out.println("balance"+balance.toString());
                    balance = balance.add(transactionOutput.getValue());
                }
            }
        }
        return balance;
    }

    private void notifyBalanceListeners() {
        for (BalanceListener balanceListener : balanceListeners) {
            Coin balance;
            if (balanceListener.getAddress() != null)
                balance = getAddressBalance(balanceListener.getAddress());
            else
                balance = getWalletBalance();

            balanceListener.onBalanceChanged(balance);
        }
    }

    public Coin getWalletBalance() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }

    // get amount to send
    public String getamountToSend(){
    	return amountToSend;
    }
    
    // set amount to send
    public void setamountToSend(String amount){
    	amountToSend = amount;
    }

    
    //add a new account to NodeInfo
	 public void addNewAccount() {
	     Account account = new Account();
	     Address address = account.getAddress();
	     NodeInfo.put(address, account);
	 }
	 
	 public Coin getBalance() {
	    return wallet.getWatchedBalance();
	 }
	 
	 //set a balance on NodeInfo
	 public void setBalance(Address _address, BigInteger amount) {
	    NodeInfo.get(_address).setBalance(amount);
	 }

    /*******************************************************************************************************************
        **Send**
    *******************************************************************************************************************/

    public void sendFunds(String withdrawFromAddress,String withdrawToAddress, Coin amount, FutureCallback<Transaction> callback) throws AddressFormatException,
            InsufficientMoneyException, IllegalArgumentException {
        Transaction tx = new Transaction(netParams);
        tx.addOutput(amount.subtract(NodeFee.TX_FEE), new Address(netParams, withdrawToAddress));

        Wallet.SendRequest sendRequest = Wallet.SendRequest.forTx(tx);
        sendRequest.shuffleOutputs = false;
        //get address from nodeinfo

        //sendRequest.coinSelector = new AddressBasedCoinSelector(netParams, //address , true);
        //sendRequest.changeAddress;

        Wallet.SendResult sendResult = wallet.sendCoins(sendRequest);
        Futures.addCallback(sendResult.broadcastComplete, callback);

         System.out.print("sendFunds: " + tx);
    }




    /*******************************************************************************************************************
         **Wallet**
     *******************************************************************************************************************/
	//create a wallet
    public void createWallet(){
        wallet = walletKit.wallet();
        //      wallet = new Wallet(netParams, keyChainGroup);
	 }


    public void addNewAddress() {
        this.wallet.freshReceiveAddress();
     
    }

    public void loadWallet () throws UnreadableWalletException {
        File sana = new File("DECENTRAL.wallet");
        System.out.println(walletKit.wallet().loadFromFile(sana).toString());
    }

    // load a wallet
	 public void loadWallet(String filename) throws BlockStoreException, UnknownHostException {
			 // wallet file that contains Bitcoins we can send
	         walletFile = new File(filename);	
		     // load wallet from file
		     try {
				wallet = Wallet.loadFromFile(walletFile);


                 //peerGroup.;
                 System.out.println("You have : " + wallet.getWatchedBalance() + " bitcoins" + wallet.toString());
                 System.exit(0);

                 System.out.println(wallet.toString());
			 } catch (UnreadableWalletException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}		           		
	 }

    public void encryptWallet(String passwordDigest) {
        this.wallet.encrypt(passwordDigest);
    }

    public void decryptWallet(String passwordDigest) {
        this.wallet.decrypt(passwordDigest);
    }

     //total Node's Bitcoin wallet balance
	 public BigInteger getTotalNodeBalance() {

	        BigInteger sum = BigInteger.ZERO;
	        
	        for (Account account : NodeInfo.values()) {
	            sum = sum.add(account.getBalance());
	        }
	        System.out.println(sum);
	        return sum;
	   }

      /*******************************************************************************************************************
         **Transaction**
       *******************************************************************************************************************/

	 //build transaction
	 public void buildTransaction(Transaction transaction, String passwordDigest, String amountToSend, String recipient) throws BlockStoreException, AddressFormatException, InsufficientMoneyException, IOException{

		    // initialize BlockChain object
		    //chain = new BlockChain(netParams, wallet, blockStore);
		
		    // instantiate Peer object to handle connections
		    // final Peer peer = new Peer(netParams, null, new PeerAddress(InetAddress.getLocalHost()), chain, null);

		             
		    // recipient address provided by official Bitcoin client
		    Address recipientAddress = new Address(netParams, recipient);
             Wallet.SendRequest req = Wallet.SendRequest.to(recipientAddress, Coin.parseCoin(amountToSend));
             req.aesKey = wallet.getKeyCrypter().deriveKey("passwordDigest");
             wallet.sendCoins(req);

		   // save wallet with new transaction(s)
		   wallet.saveToFile(walletFile);
		 
	 }

	 
	 //serialize a transaction
	 public ECKey.ECDSASignature  serializeMultisigTransaction (ECKey key, Transaction contracts ){
			// Assume we get the multisig transaction we're trying to spend from 
			// somewhere, like a network connection.
			ECKey serverKey = key ;
			Transaction contract = contracts;
			Address clientKey = null;
			
			TransactionOutput multisigOutput = contract.getOutput(0);
			Script multisigScript = multisigOutput.getScriptPubKey();
			// Is the output what we expect?
			//checkState(multisigScript.isSentToMultiSig());
			//BigInteger value = multisigOutput.getValue();
	
			// OK, now build a transaction that spends the money back to the client.
			Transaction spendTx = new Transaction(netParams);
	
			//spendTx.addOutput(value, clientKey);
			spendTx.addInput(multisigOutput);
	
			// It's of the right form. But the wallet can't sign it. So, we have to
			// do it ourselves.
			Sha256Hash sighash = spendTx.hashForSignature(0, multisigScript, Transaction.SigHash.ALL, false);
			ECKey.ECDSASignature signature = serverKey.sign(sighash);
			// We have calculated a valid signature, so send it back to the client:
			return signature;
		
	 }
	 /* deserialize transactions*/
   // public void deserializeMultisigTransaction(Transaction transaction) throws TransactionRequiredException{


    //}



    /*******************************************************************************************************************
        **Multisignature**
     *******************************************************************************************************************/
	 // create a 2 of 3 p2sh account
	 public Address createMultisigScript(ECKey clientKey, ECKey clientKey2) throws BlockStoreException, InsufficientMoneyException, InterruptedException, ExecutionException, AddressFormatException {
		 // initialize BlockChain object
		 //chain = new BlockChain(netParams, wallet, blockStore);
		 //PeerGroup peerGroup = new PeerGroup(netParams, chain);
		 //The Node's Key for This Account
		 ECKey serverKey = new ECKey();

		 // Prepare a template for the contract.
		 Transaction contract = new Transaction(netParams);
		 List<ECKey> keys = ImmutableList.of(clientKey, clientKey2, serverKey);

		 /* Create a 2 of 3 MULTISIG redeem Script  */
		 Script redeemScript = ScriptBuilder.createMultiSigOutputScript(3, keys);
         /* Create a p2sh output script from the redeemScript */
         Script multisig = ScriptBuilder.createP2SHOutputScript(redeemScript);
         //format to address
         Address   multisigAddress = Address.fromP2SHScript(netParams, multisig);
         //return multisigAddress
         System.out.print(multisigAddress.toString());
         return multisigAddress;
		 
	 }

    private Script getMultiSigScript(String offererPubKey, String takerPubKey, String arbitratorPubKey) {
        ECKey offererKey = ECKey.fromPublicOnly(Utils.parseAsHexOrBase58(offererPubKey));
        ECKey takerKey = ECKey.fromPublicOnly(Utils.parseAsHexOrBase58(takerPubKey));
        ECKey arbitratorKey = ECKey.fromPublicOnly(Utils.parseAsHexOrBase58(arbitratorPubKey));

        List<ECKey> keys = ImmutableList.of(offererKey, takerKey, arbitratorKey);
        return ScriptBuilder.createMultiSigOutputScript(2, keys);
    }


    private Transaction createP2SH(String depositTxAsHex, Coin offererPaybackAmount, Coin takerPaybackAmount,
                                       String offererAddress, String takerAddress) throws AddressFormatException {

        Transaction depositTx = new Transaction(netParams, Utils.parseAsHexOrBase58(depositTxAsHex));
        TransactionOutput multiSigOutput = depositTx.getOutput(0);
        Transaction tx = new Transaction(netParams);
        tx.addInput(multiSigOutput);
        tx.addOutput(offererPaybackAmount, new Address(netParams, offererAddress));
        tx.addOutput(takerPaybackAmount, new Address(netParams, takerAddress));
        return tx;
    }


}