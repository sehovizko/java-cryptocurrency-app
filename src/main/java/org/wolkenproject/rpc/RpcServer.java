package org.wolkenproject.rpc;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wolkenproject.core.Address;
import org.wolkenproject.core.BlockIndex;
import org.wolkenproject.core.Context;
import org.wolkenproject.core.transactions.Transaction;
import org.wolkenproject.crypto.Keypair;
import org.wolkenproject.encoders.Base16;
import org.wolkenproject.encoders.Base58;
import org.wolkenproject.exceptions.WolkenException;
import org.wolkenproject.network.Message;
import org.wolkenproject.network.Node;
import org.wolkenproject.network.messages.Inv;
import org.wolkenproject.utils.Logger;
import org.wolkenproject.utils.VoidCallableThrowsT;
import org.wolkenproject.wallet.Wallet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class RpcServer {
    private HttpServer          server;
    private Context             context;
    private UrlPath[]           paths;
    private Set<Request>        handlers;
    private ExecutorService     executor;
    private Wallet              wallet;
    private byte                passphrase[];
    private long                passphraseTimeout;
    private long                passphraseTimestamp;
    private ReentrantLock       mutex;

    public RpcServer(Context context, int port) throws IOException {
        Logger.alert("=============================================");
        Logger.alert("Starting HTTP server");
        Logger.alert("=============================================");

        server      = HttpServer.create(new InetSocketAddress(port), 12);
        handlers    = new LinkedHashSet<>();
        mutex       = new ReentrantLock();

        onGet("/", response -> response.sendFile("/rpc/index.html"));
        onGet("/home", response -> response.sendFile("/rpc/index.html"));
        onGet("/dashboard", response -> response.sendFile("/rpc/dashboard.html"));
        onGet("/portfolio", response -> response.sendFile("/rpc/portfolio.html"));
        onGet("/login", response -> response.sendFile("/rpc/login.html"));
        onGet("/api", RpcServer::apiRequest);
        onGet("/content/:filename", response -> response.sendFile("/rpc/${filename}"));

        server.createContext("/", exchange -> {
            Set<Request> handlers =Context.getInstance().getRPCServer().getHandlers();
            String query           = exchange.getRequestURI().getQuery();

            if (query == null) {
                query = "";
            }

            String url             = exchange.getRequestURI().toString();

            if (!query.isEmpty()) {
                url = url.replace(query, "");
                url = url.substring(0, url.length() - 1);
            }

            for (Request request : handlers) {
                if (request.submit(url)) {
                    request.call(exchange, url, query);
                    break;
                }
            }

        });

        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            while (Context.getInstance().isRunning()) {
                Context.getInstance().getRPCServer().maintain();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                }
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    private void maintain() {
        if (getPassphrase() != null) {
            long now = System.currentTimeMillis();
            if (now - getPassphraseTimestamp() >= getPassphraseTimeout()) {
                setPassphrase(null, 0);
            }
        }
    }

    public Set<Request> getHandlers() {
        return handlers;
    }

    public static void apiRequest(Messenger msg) throws IOException {
        JSONObject request = msg.getFormattedQuery();
        JSONObject response= new JSONObject();
        String requestType = request.getString("request");

        if (request.getString("request").equals("close")) {
            String password = request.getString("password");
            response.put("response", "success");
            msg.send("application/json", response.toString().getBytes());
            Context.getInstance().shutDown();
            return;
        } else if (requestType.equals("createwallet")) {
            if (!request.has("name")) {
                response.put("response", "failed");
                response.put("reason", "'createwallet' command requires an argument 'name'.");
            } else {
                String name = request.getString("name");
                if (Context.getInstance().getDatabase().checkWalletExists(name)) {
                    response.put("response", "failed");
                    response.put("reason", "a wallet with the name '" + name + "' already exists.");
                } else {
                    response.put("response", "success");
                    String password = request.getString("password");
                    byte pass[]     = password.getBytes();

                    if (!request.getBoolean("encrypt")) {
                        pass        = null;
                    }

                    try {
                        Wallet wallet = new Wallet(name, pass);
                        Context.getInstance().getDatabase().storeWallet(wallet);
                        response.put("content", wallet.toJson());
                    } catch (WolkenException e) {
                        response.put("response", "failed");
                        response.put("reason", e.getMessage());
                    }
                }
            }
        } else if (requestType.equals("encryptwallet")) {
            String name = request.getString("name");
            String pass = request.getString("password");

            if (!Context.getInstance().getDatabase().checkWalletExists(name)) {
                response.put("response", "failed");
                response.put("reason", "wallet '" + name + "' does not exist.");
            } else {
                Wallet wallet = Context.getInstance().getDatabase().getWallet(name);
                if (wallet.isEncrypted()) {
                    response.put("response", "failed");
                    response.put("reason", "wallet is already encrypted, use 'walletpassphrasechange' to change the passphrase.");
                } else {
                    try {
                        wallet = wallet.encrypt(pass.getBytes());
                    } catch (WolkenException e) {
                        response.put("response", "failed");
                        response.put("reason", e.getMessage());
                    }
                    Context.getInstance().getDatabase().storeWallet(wallet);
                    response.put("response", "success");
                }
            }
        } else if (requestType.equals("dumpwallet")) {
            String name = request.getString("name");

            if (!Context.getInstance().getDatabase().checkWalletExists(name)) {
                response.put("response", "failed");
                response.put("reason", "wallet '" + name + "' does not exist.");
            } else {
                Wallet wallet = Context.getInstance().getDatabase().getWallet(name);
                response.put("response", "success");
                response.put("content", wallet.toJson());
            }
        } else if (requestType.equals("walletpassphrasechange")) {
            String name = request.getString("name");
            String old  = request.getString("old");
            String neu  = request.getString("new");

            if (!Context.getInstance().getDatabase().checkWalletExists(name)) {
                response.put("response", "failed");
                response.put("reason", "wallet '" + name + "' does not exist.");
            } else {
                Wallet wallet   = Context.getInstance().getDatabase().getWallet(name);
                try {
                    wallet          = wallet.changePassphrase(old.getBytes(), neu.getBytes());
                    response.put("response", "success");
                    response.put("content", wallet.toJson());
                    Context.getInstance().getDatabase().storeWallet(wallet);
                }
                catch (WolkenException e) {
                    response.put("response", "failed");
                    response.put("reason", e.getMessage());
                }
            }
        } else if (requestType.equals("walletpassphrase")) {
            long timeout    = request.getLong("timeout");
            String password = request.getString("password");
            Context.getInstance().getRPCServer().setPassphrase(password.getBytes(), timeout);
            response.put("response", "success");
        } else if (requestType.equals("loadwallet")) {
            String name = request.getString("name");

            if (!Context.getInstance().getDatabase().checkWalletExists(name)) {
                response.put("response", "failed");
                response.put("reason", "wallet '" + name + "' does not exist.");
            } else {
                Context.getInstance().getRPCServer().setWallet(Context.getInstance().getDatabase().getWallet(name));
            }
        } else if (requestType.equals("getaccount")) {
            String encodedAddress   = request.getString("address");
            Address address         = null;

            if (!Base58.isEncoded(encodedAddress)) {
                response.put("response", "failed");
                response.put("reason", "provided address '" + encodedAddress + "' is not base58 encoded.");
            } else if (!Address.isValidAddress(Base58.decode(encodedAddress))) {
                response.put("response", "failed");
                response.put("reason", "address '" + encodedAddress + "' is not valid.");
            } else if (!Context.getInstance().getDatabase().checkAccountExists((address = Address.fromFormatted(Base58.decode(encodedAddress))).getRaw())) {
                response.put("response", "failed");
                response.put("reason", "address '" + encodedAddress + "' does not exist.");
            } else {
                response.put("response", "success");
                response.put("content", Context.getInstance().getDatabase().findAccount(address.getRaw()).toJson());
            }
        } else if (requestType.equals("walletfromdump")) {
            String dump             = request.getString("dump");

            try {
                Wallet wallet           = new Wallet(dump);

                if (Context.getInstance().getDatabase().checkWalletExists(wallet.getName())) {
                    Wallet other        = Context.getInstance().getDatabase().getWallet(wallet.getName());

                    if (wallet.equals(other)) {
                        response.put("response", "failed");
                        response.put("reason", "wallet already exists.");
                    } else {
                        response.put("response", "failed");
                        response.put("reason", "another wawllet with the name '" + wallet.getName() + "' already exists.");
                    }
                } else {
                    response.put("response", "success");
                }
            } catch (WolkenException e) {
                response.put("response", "failed");
                response.put("reason", e.getMessage());
            }
        } else if (requestType.equals("signtransaction")) {
            JSONObject transaction      = request.getJSONObject("transaction");

            try {
                Transaction tx          = Transaction.fromJson(transaction);
                Wallet wallet           = Context.getInstance().getRPCServer().getWallet();
                if (wallet != null) {
                    Keypair keypair     = wallet.getKeypairForSigning(Context.getInstance().getRPCServer().getPassphrase());
                    Transaction signed  = tx.sign(keypair);
                    response.put("response", "success");
                    response.put("content", signed.toJson());
                }
            } catch (WolkenException e) {
                response.put("response", "failed");
                response.put("reason", e.getMessage());
            }
        } else if (requestType.equals("broadcasttransaction")) {
            JSONObject transaction      = request.getJSONObject("transaction");

            try {
                Transaction tx          = Transaction.fromJson(transaction);
                if (!tx.shallowVerify()) {
                    response.put("response", "failed");
                    response.put("reason", "invalid transaction.");
                } else {
                    Context.getInstance().getTransactionPool().add(tx);
                    Set<byte[]> hash = new LinkedHashSet<>();
                    hash.add(tx.getHash());
                    Message message = new Inv(Inv.Type.Transaction, hash);

                    Context.getInstance().getServer().broadcast(message);
                    response.put("response", "success");
                    response.put("content", "transaction broadcast to '" + Context.getInstance().getServer().getConnectedNodes().size() + "' peers.");
                }
            } catch (WolkenException e) {
                response.put("response", "failed");
                response.put("reason", e.getMessage());
            }
        } else if (requestType.equals("gettransaction")) {
            String txid                 = request.getString("txid");

            if (!Base16.isEncoded(txid)) {
                response.put("response", "failed");
                response.put("reason", "expected 'txid' to be base16 encoded.");
            } else {
                byte hash[]             = Base16.decode(txid);
                Transaction transaction = null;

                if (Context.getInstance().getDatabase().checkTransactionExists(hash)) {
                    transaction = Context.getInstance().getDatabase().findTransaction(hash);
                } else if (Context.getInstance().getTransactionPool().contains(hash)) {
                    transaction = Context.getInstance().getTransactionPool().getTransaction(hash);
                }

                if (transaction == null) {
                    response.put("response", "failed");
                    response.put("reason", "could not find transaction '"+txid+"'.");
                } else {
                    response.put("response", "success");
                    response.put("content", transaction.toJson());
                }
            }
        } else if (request.getString("request").equals("server")) {
            response.put("response", "success");
            Set<Node> nodes = Context.getInstance().getServer().getConnectedNodes();

            if (request.has("connected") && request.getBoolean("connected")) {
                response.put("numconnected", nodes.size());
            }

            if (request.has("nodes") && request.getBoolean("nodes")) {
                JSONArray array = new JSONArray();
                int counter = 0;
                for (Node node : nodes) {
                    array.put(counter ++, node.toJson());
                }
                response.put("nodes", array);
            }
        }

        msg.send("application/json", response.toString().getBytes());
    }

    private byte[] getPassphrase() {
        mutex.lock();
        try {
            return passphrase;
        } finally {
            mutex.unlock();
        }
    }

    private long getPassphraseTimeout() {
        mutex.lock();
        try {
            return passphraseTimeout;
        } finally {
            mutex.unlock();
        }
    }

    private long getPassphraseTimestamp() {
        mutex.lock();
        try {
            return passphraseTimestamp;
        } finally {
            mutex.unlock();
        }
    }

    private Wallet getWallet() {
        mutex.lock();
        try {
            return wallet;
        } finally {
            mutex.unlock();
        }
    }

    private void setWallet(Wallet wallet) {
        mutex.lock();
        try {
            this.wallet = wallet;
        } finally {
            mutex.unlock();
        }
    }

    private void setPassphrase(byte[] passphrase, long timeout) {
        mutex.lock();
        try {
            if (this.passphrase != null) {
                // objects remain in memory until the garbage
                // collector removes them by setting the contents
                // of the array to random bytes we can ensure that
                // the data has been removed safely.
                new Random().nextBytes(this.passphrase);
                this.passphrase             = passphrase;
                this.passphraseTimeout      = timeout;
                this.passphraseTimestamp    = System.currentTimeMillis();
            }
        } finally {
            mutex.unlock();
        }
    }

    protected void onGet(String requestURL, VoidCallableThrowsT<Messenger, IOException> function) {
        boolean mustMatch = !requestURL.contains(":");

        handlers.add(new Request(requestURL, mustMatch, function));
    }

    public void stop() {
        Logger.alert("stopping rpc server.");
        server.stop(0);
        executor.shutdownNow();
        Logger.alert("rpc server stopped.");
    }

    private static void traversePath(String url) {
    }

    public static void onContentMsg(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "text/html");
    }

    public static void onApiMsg(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/json");
    }

    public static void onBlockMsg(HttpExchange exchange) throws IOException {
        JSONObject message  = readJson(exchange.getRequestBody());
        String blockId      = message.getString("hash");
        boolean txList      = message.getBoolean("transactions");
        boolean txHash      = message.getBoolean("only-txid");
        boolean evList      = message.getBoolean("events");
        boolean evHash      = message.getBoolean("only-evid");
        boolean txEvt       = message.getBoolean("format");

        byte blockHash[]    = Base16.decode(blockId);

        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/json");

        if (Context.getInstance().getDatabase().checkBlockExists(blockHash)) {
            BlockIndex block= Context.getInstance().getDatabase().findBlock(blockHash);

            JSONObject response = new JSONObject();
            response.put("type", "success");
            response.put("block", block.toJson(txList, txHash, evList, evHash, txEvt));
            sendResponse(200, response, exchange);
        } else {
            JSONObject response = new JSONObject();
            response.put("type", "fail");
            response.put("reason", "could not find requested block");
            sendResponse(200, response, exchange);
        }
    }

    public static void onTransactionMsg(HttpExchange exchange) throws IOException {
        JSONObject message  = readJson(exchange.getRequestBody());
        String txId         = message.getString("hash");
        boolean evList      = message.getBoolean("events");
        boolean evHash      = message.getBoolean("only-evid");

        byte txHash[]       = Base16.decode(txId);

        if (Context.getInstance().getDatabase().checkTransactionExists(txHash)) {
            Transaction transaction = Context.getInstance().getDatabase().findTransaction(txHash);

            JSONObject response = new JSONObject();
            response.put("type", "success");
            response.put("transaction", transaction.toJson(evList, evHash));
            sendResponse(200, response, exchange);
        } else {
            JSONObject response = new JSONObject();
            response.put("type", "fail");
            response.put("reason", "could not find requested block");
            sendResponse(200, response, exchange);
        }
    }

    public static void onSubmitMsg(HttpExchange exchange) {
    }

    public static void onWalletMsg(HttpExchange exchange) {
    }

    public static final JSONObject readJson(InputStream inputStream) throws IOException {
        return new JSONObject(readUTF(inputStream));
    }

    private static final String readUTF(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line = "";
        StringBuilder builder = new StringBuilder();
        while (( line = reader.readLine() ) != null ) {
            builder.append(line).append("\n");
        }

        reader.close();
        return builder.toString();
    }

    private static final void sendResponse(int responseCode, JSONObject response, HttpExchange exchange) throws IOException {
        sendResponse(responseCode, response.toString(), exchange);
    }

    private static final void sendResponse(int responseCode, String response, HttpExchange exchange) throws IOException {
        byte actualResponse[] = response.getBytes();
        exchange.sendResponseHeaders(responseCode, actualResponse.length);
        exchange.getResponseBody().write(actualResponse);
    }

    public UrlPath[] getPaths() {
        return paths;
    }
}
