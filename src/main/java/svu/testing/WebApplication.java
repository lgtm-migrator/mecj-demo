package svu.testing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import svu.meclassifier.MinkowskiDistanceFunction;
import svu.meclassifier.MultimodalEvolutionaryClassifier;
import svu.proteinsequences.DecisionRuleClassifier;
import svu.proteinsequences.ProteinSequence;
import svu.proteinsequences.ProteinSequenceClassifiers;
import svu.util.CSVUtil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class WebApplication {

	private static MultimodalEvolutionaryClassifier mec = null;
	
	private static DecisionRuleClassifier psCls = null;
	
	private static double param(Map<String, String> req, String key) {
		return Double.parseDouble(req.get(key));
	}

	public static Map<String, String> queryToMap(String query){
	    Map<String, String> result = new HashMap<String, String>();
	    if (query == null)
	    	return result;
	    
	    for (String param : query.split("&")) {
	        String pair[] = param.split("=");
	        if (pair.length>1) {
	            result.put(pair[0], pair[1]);
	        }else{
	            result.put(pair[0], "");
	        }
	    }
	    return result;
	}		
	
	private static double[] params(HttpExchange t) {
		
		Map<String, String> req = queryToMap(t.getRequestURI().getQuery());
		
		return new double[]{ 
				param(req, "preg"),
				param(req, "plas"),
				param(req, "pres"),
				param(req, "skin"),
				param(req, "insu"),
				param(req, "mass"),
				param(req, "pedi"),
				param(req, "age")
		};
	}

	public static void sendResponse(HttpExchange t, int code, String message, String contentType) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.write(message.getBytes());
		sendResponse(t,  code,  b,  contentType);
	}

	public static void sendResponse(HttpExchange t, int code, ByteArrayOutputStream message, String contentType) throws IOException {
		long length = message.size();
		if (contentType != null) t.getResponseHeaders().add("Content-Type", contentType);
		t.sendResponseHeaders(code, length);
		OutputStream os = t.getResponseBody();
		os.write(message.toByteArray());
		os.close();
	}
	
	public static Map<String, String> CONTENT_TYPES = new HashMap<String, String>();
	static {
		CONTENT_TYPES.put("png", "image/png");
		CONTENT_TYPES.put("js", "application/javascript");
		CONTENT_TYPES.put("html", "text/html");
		CONTENT_TYPES.put("css", "text/css");
	}

	public static class StaticContentHandler implements HttpHandler {
		private File path;
		private String prefix;
		public StaticContentHandler(String prefix, File path) {
			this.prefix = prefix;
			this.path = path;
		}
		@Override
		public void handle(HttpExchange t) throws IOException {
			String path = t.getRequestURI().getPath().substring(prefix.length());
			File get = new File(this.path, path);
			if (!get.exists())
				sendResponse(t, 404, path, "text/plain");
			else {
				FileInputStream fr = new FileInputStream(get);
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				while (true) {
					int read = fr.read(buf);
					if (read == -1)
						break;
					b.write(buf, 0, read);
				}
				fr.close();
				String ext = get.getName().substring(get.getName().lastIndexOf(".") + 1);
				String ct = CONTENT_TYPES.get(ext.toLowerCase());
				sendResponse(t, 200, b, ct);
			}
		}
	}
	
	public static class PredictionHandler implements HttpHandler {
		
		@Override
		public void handle(HttpExchange t) throws IOException {
			if (mec == null) {
				sendResponse(t, 500, "Classifier not ready", "text/plain");
			}
			else {
				double[] newUserParams = params(t);
				int[] prediction = mec.predict(new double[][]{ newUserParams });
				// send reply to client
				sendResponse(t, 200, "{\"result\":" + prediction[0] + "}", "application/json");
			}
		}
	}
	
	public static class ProteinSequenceHandler implements HttpHandler {
		
		@Override
		public void handle(HttpExchange t) throws IOException {
			if (psCls == null) {
				sendResponse(t, 500, "Classifier not ready", "text/plain");
			}
			else {
				
				Map<String, String> req = queryToMap(t.getRequestURI().getQuery());
				String seq = req.get("seq");
				double[] seqD = ProteinSequence.stringToDoubles(seq);
				int[] prediction = psCls.predict(new double[][]{ seqD });
				// send reply to client
				sendResponse(t, 200, "{\"result\":" + prediction[0] + "}", "application/json");
			}
		}
	}


	public static void main(String[] args) throws Exception {

		String userDir = System.getProperty("user.dir");
		File path = new File(new File(new File(userDir, "src"), "main"), "public");
		System.out.println("path = " + path);
		
		HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
		server.createContext("/public", new StaticContentHandler("/public", path));
		server.createContext("/predict", new PredictionHandler());
		server.createContext("/predict-ps", new ProteinSequenceHandler());
		server.start();
		
		System.out.println("Web-server started, please open http://127.0.0.1:8080/public/index.html");
		
		MultimodalEvolutionaryClassifier classifier =
				new MultimodalEvolutionaryClassifier(100, MinkowskiDistanceFunction.Factory(3.0));
		
		psCls = ProteinSequenceClassifiers.newStatic();
		
		// read dataset
		Reader reader = new InputStreamReader(WebApplication.class.getResourceAsStream("/diabetes.csv"));
		double[][] data = CSVUtil.load(reader);
		double[][] X = CSVUtil.attributes(data);
		int[]      Y = CSVUtil.classes(data);

		classifier.fit(X, Y); // train the classifier
		mec = classifier; // make it ready for the web app
		
		System.out.println("Classifier ready for use.");
	}
}
