package weka.finito;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Hashtable;

import java.lang.System;

import security.DGK.DGKKeyPairGenerator;
import security.DGK.DGKOperations;
import security.DGK.DGKPublicKey;
import security.misc.HomomorphicException;
import security.paillier.PaillierCipher;
import security.paillier.PaillierKeyPairGenerator;
import security.paillier.PaillierPublicKey;
import security.socialistmillionaire.bob;
import weka.finito.structs.BigIntegers;

public final class client implements Runnable {
	private final String features_file;
	private final int key_size;
	private final int precision;

	private final String [] level_site_ips;
	private final int [] level_site_ports;
	private final int port;

	private String classification = null;

	private KeyPair dgk;
	private KeyPair paillier;
	private Hashtable<String, BigIntegers> feature = null;
	private String next_index = null;
	private String iv = null;
	private boolean classification_complete = false;
	private String [] classes;

	private DGKPublicKey dgk_public_key;
	private PaillierPublicKey paillier_public_key;
	private final HashMap<String, String> hashed_classification = new HashMap<>();
	private boolean talk_to_server_site = true;

    //For k8s deployment.
    public static void main(String[] args) {
        // Declare variables needed.
        int key_size = -1;
        int precision = -1;
        String level_site_string;
        int port = -1;

        // Read in our environment variables.
        level_site_string = System.getenv("LEVEL_SITE_DOMAINS");
        if(level_site_string == null || level_site_string.isEmpty()) {
            System.out.println("No level site domains provided.");
            System.exit(1);
        }
        String[] level_domains = level_site_string.split(",");

        try {
            port = Integer.parseInt(System.getenv("PORT_NUM"));
        } catch (NumberFormatException e) {
            System.out.println("No port provided for the Level Sites.");
            System.exit(1);
        }

        try {
            precision = Integer.parseInt(System.getenv("PRECISION"));
        } catch (NumberFormatException e) {
            System.out.println("No Precision value provided.");
            System.exit(1);
        }

        try {
            key_size = Integer.parseInt(System.getenv("PPDT_KEY_SIZE"));
        } catch (NumberFormatException e) {
            System.out.println("No crypto key provided value provided.");
            System.exit(1);
        }

		client test = null;
		if (args.length == 1) {
			test = new client(key_size, args[0], level_domains, port, precision, true);
		}
		else if (args.length == 2) {
			test = new client(key_size, args[0], level_domains, port, precision, false);
		}
		else {
			System.out.println("Missing Testing Data set as an argument parameter");
			System.exit(1);
		}


		test.run();

        System.exit(0);
    }

	// For local host testing
	public client(int key_size, String features_file, String [] level_site_ips, int [] level_site_ports,
				  int precision, boolean talk_to_server_site) {
		this.key_size = key_size;
		this.features_file = features_file;
		this.level_site_ips = level_site_ips;
		this.level_site_ports = level_site_ports;
		this.precision = precision;
		this.port = -1;
		this.talk_to_server_site = talk_to_server_site;
	}

	public client(int key_size, String features_file, String [] level_site_ips, int port,
				  int precision, boolean talk_to_server_site) {
		this.key_size = key_size;
		this.features_file = features_file;
		this.level_site_ips = level_site_ips;
		this.level_site_ports = null;
		this.precision = precision;
		this.port = port;
		this.talk_to_server_site = talk_to_server_site;
	}

	public void generate_keys() {
		// Generate Key Pairs
		DGKKeyPairGenerator p = new DGKKeyPairGenerator();
		p.initialize(key_size, null);
		dgk = p.generateKeyPair();

		PaillierKeyPairGenerator pa = new PaillierKeyPairGenerator();
		p.initialize(key_size, null);
		paillier = pa.generateKeyPair();

		dgk_public_key = (DGKPublicKey) dgk.getPublic();
		paillier_public_key = (PaillierPublicKey) paillier.getPublic();
	}

	public static String hash(String text) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
		return Base64.getEncoder().encodeToString(hash);
	}

	// Used for set-up
	private void communicate_with_server_site(PaillierPublicKey paillier, DGKPublicKey dgk)
			throws IOException, ClassNotFoundException {
		try (Socket server_site = new Socket("127.0.0.1", 10000)) {
			ObjectOutputStream to_server_site = new ObjectOutputStream(server_site.getOutputStream());
			ObjectInputStream from_server_site = new ObjectInputStream(server_site.getInputStream());

			// Receive a message from the client to get their keys
			to_server_site.writeObject(paillier);
			to_server_site.writeObject(dgk);
			to_server_site.flush();

			// Get leaves from Server-site
			Object o = from_server_site.readObject();
			classes = (String []) o;
		}
	}

	// Get Classification after Evaluation
	public String getClassification() {
		return this.classification;
	}


	// Evaluation
	private Hashtable<String, BigIntegers> read_features(String path,
														PaillierPublicKey paillier_public_key,
														DGKPublicKey dgk_public_key,
														int precision)
					throws IOException, HomomorphicException {

		BigInteger integerValuePaillier;
		BigInteger integerValueDGK;
		int intermediateInteger;
		Hashtable<String, BigIntegers> values = new Hashtable<>();
		try (BufferedReader br = new BufferedReader(new FileReader(path))) {
			String line;

			while ((line = br.readLine()) != null) {
				String key, value;
				String[] split = line.split("\\t");
				key = split[0];
				value = split[1];
				if (value.equals("t") || (value.equals("yes"))) {
					value = "1";
				}
				if (value.equals("f") || (value.equals("no"))) {
					value = "0";
				}
				if (value.equals("other")) {
					value = "1";
				}
				System.out.println("Initial value:" + value);
				intermediateInteger = (int) (Double.parseDouble(value) * Math.pow(10, precision));
				System.out.println("Value to be compared with:" + intermediateInteger);
				integerValuePaillier = PaillierCipher.encrypt(intermediateInteger, paillier_public_key);
				integerValueDGK = DGKOperations.encrypt(intermediateInteger, dgk_public_key);
				values.put(key, new BigIntegers(integerValuePaillier, integerValueDGK));
			}
			return values;
		}
	}

	// Function used to Evaluate
	private void communicate_with_level_site(Socket level_site)
			throws IOException, ClassNotFoundException, HomomorphicException {
		// Communicate with each Level-Site
		Object o;
		bob client;

		// Create I/O streams
		ObjectOutputStream to_level_site = new ObjectOutputStream(level_site.getOutputStream());
		ObjectInputStream from_level_site = new ObjectInputStream(level_site.getInputStream());

		// Send the encrypted data to Level-Site
		to_level_site.writeObject(this.feature);
		to_level_site.flush();

		// Send the Public Keys using Alice and Bob
		client = new bob(level_site, paillier, dgk);

		// Send bool:
		// 1- true, there is an encrypted index coming
		// 2- false, there is NO encrypted index coming
		if (next_index == null) {
			to_level_site.writeBoolean(false);
		}
		else {
			to_level_site.writeBoolean(true);
			to_level_site.writeObject(next_index);
			to_level_site.writeObject(iv);
		}
		to_level_site.flush();

		// Work with the comparison
		int comparison_type;
		while(true) {
			comparison_type = from_level_site.readInt();
			if (comparison_type == -2) {
				System.out.println("LEVEL-SITE DOESN'T HAVE DATA!!!");
				this.classification_complete = true;
				return;
			}
			else if (comparison_type == -1) {
				break;
			}
			else if (comparison_type == 0) {
				client.setDGKMode(false);
			}
			else if (comparison_type == 1) {
				client.setDGKMode(true);
			}
			client.Protocol4();
		}

		// Get boolean from level-site:
		// true - get leaf value
		// false - get encrypted AES index for next round
		classification_complete = from_level_site.readBoolean();
		o = from_level_site.readObject();
		if (classification_complete) {
			if (o instanceof String) {
				classification = (String) o;
				classification = hashed_classification.get(classification);
			}
		}
		else {
			if (o instanceof String) {
				next_index = (String) o;
			}
			o = from_level_site.readObject();
			if (o instanceof String) {
				iv = (String) o;
			}
		}
	}

	// Function used to Evaluate
	public void run() {

		try {
			// Don't regenerate keys if you are just using a different VALUES file
			if (talk_to_server_site) {
				generate_keys();
			}

			feature = read_features(features_file, paillier_public_key, dgk_public_key, precision);

			// Client needs to give server-site public key (to give to level-sites)
			// Client needs to know all possible classes...
			if (talk_to_server_site) {
				// Don't send keys to server-site to ask for classes since now it is assumed level-sites are up
				communicate_with_server_site(paillier_public_key, dgk_public_key);
				for (String aClass : classes) {
					hashed_classification.put(hash(aClass), aClass);
				}
				// Make sure level-sites got everything...
				Thread.sleep(2000);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		int connection_port;
		long start_time = System.nanoTime();
		try {

			for (int i = 0; i < level_site_ips.length; i++) {
				if (classification_complete) {
					break;
				}
				if (port == -1) {
					assert level_site_ports != null;
					connection_port = level_site_ports[i];
					System.out.println("Local Test: " + level_site_ips[i] + ":" + level_site_ports[i]);
				}
				else {
					connection_port = port;
				}

				try(Socket level_site = new Socket(level_site_ips[i], connection_port)) {
					System.out.println("Client connected to level " + i);
					communicate_with_level_site(level_site);
				}
			}
            long end_time = System.nanoTime();
			System.out.println("The Classification is: " + classification);
			double run_time = (double) (end_time - start_time);
			run_time = run_time/1000000;
            System.out.printf("It took %f ms to classify\n", run_time);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
}
