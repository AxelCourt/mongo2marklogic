package com.marklogic.mongo;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.stream.XMLStreamException;

import com.marklogic.mongo.BSON.AttributeNames;
import com.marklogic.mongo.BSON.ElementNamesBS;
import com.marklogic.xcc.Content;
import com.marklogic.xcc.ContentCreateOptions;
import com.marklogic.xcc.ContentFactory;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.ContentSourceFactory;
import com.marklogic.xcc.SecurityOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;

public class Importer {
	private static ContentCreateOptions mCreateOptions;
	private String mRoot = "";
	private String mCollection = null ;
	private int mBatchSize;
	private DocumentBatch mBatch;
	private Random mRandom = new Random();
	private ThreadPoolExecutor mPool = null;
	protected	ContentSource	mContentSource = null ;

	static class DocumentBatch {
		
		ArrayList<MongoDoc>   mBatch ;
		String  collection ;
		int mMax;
		DocumentBatch( int max )
		{
			mMax = max;
			mBatch = new ArrayList<MongoDoc>(max);
		}
		
		void put( MongoDoc doc ) {
			mBatch.add(doc);
		}

		public int size() {
			return mBatch.size();
		}
		
		public boolean isEmpty() {
			return mBatch.isEmpty();
		}

		public List<MongoDoc> contents() { return mBatch ; }

		public boolean isFull() {
			return mBatch.size() >= mMax ;
		}
		
	}
	
	

	private  class PutContent implements Runnable
	{
		DocumentBatch mContents;
		Session mSession;
		public PutContent(Session session, DocumentBatch contents) {
			mContents = contents ;
			mSession = session ;
		}

		@Override
		public void run() {
			 long tm_start  = System.currentTimeMillis() ;
			 log("Thread: " + Thread.currentThread().getName() + " Writing " + mContents.size() + " files");

			Content[] contents = new Content[ mContents.size()];
			int i = 0;
			for( MongoDoc doc : mContents.contents() )
				contents[i++] = createContent( doc );
				
			try {
				mSession.insertContent( contents );

				
				
			} catch (RequestException e) {
				log("Exception submitting data",e);
			}
			finally {
				mSession.close();
				long tm_stop = System.currentTimeMillis();
				long ms = tm_stop - tm_start ; 
				log("Thread: " + Thread.currentThread().getName() + " Completed in " + ms + " ms: " + 
				((double)mContents.size() / (ms/1000.)) + " docs/sec");
			}
		}

	}
	

	private synchronized void log(String string) {
		System.out.println(string);
		
	}
	private synchronized void log(String string, Exception e) {
		
		System.out.println(string);
		e.printStackTrace();
	}
	
	
	
	public Content createContent(MongoDoc doc) {

		Content content= ContentFactory.newContent (doc.url , doc.bytes ,mCreateOptions );
		return content ;  
		
	}

	/**
	 * @param args
	 * @throws Exception 
	 * @throws XccConfigException 
	 */
	public static void main(String[] args) throws XccConfigException, Exception {

		
		new Importer().run( args );
		
		
	}


	
	
	private void run(String[] args) throws XccConfigException, Exception {

		mRoot = BSON.getArg(args, "root", null);


		mCollection = BSON.getArg(args, "collection", null);
		
		mBatchSize = Integer.parseInt( BSON.getArg(args, "batch", "100"));
		
		String conn = BSON.getArg( args , "connection",null );
		if( conn == null ){
			usage();
		}
		mContentSource = getConnection(conn);
		int maxThreads = Integer.parseInt( BSON.getArg(args,"threads","1"));

		
		mCreateOptions = ContentCreateOptions.newXmlInstance();
		if( mCollection != null )
		     mCreateOptions.setCollections( new String[] {mCollection} );

		mPool = new ThreadPoolExecutor(maxThreads, maxThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(maxThreads * 2 ), new ThreadPoolExecutor.CallerRunsPolicy() );
		
		

		String input = BSON.getArg(args, "input", null);
		InputStream is = new BufferedInputStream(input == null ? System.in : new FileInputStream(input));

		BSONInputStream bos = new BSONInputStream(is);

		while (is.available() > 0) {
			
			BSONXMLWriter bxw = new BSONXMLWriter();
			bxw.writeDocumentNS(ElementNamesBS.document, AttributeNames.document, bos);
			put(  bxw.close() );

		}
		
		flush();
		mPool.shutdown();
		mPool.awaitTermination(100, TimeUnit.SECONDS );

		
	}
	private  void put( byte[] bytes) {
		
		if( mBatch == null )
			mBatch = new DocumentBatch(mBatchSize);
		mBatch.put( new MongoDoc( getUri() ,bytes ));
		
		if( mBatch.isFull() )
			flush();
		
		
		
		
	}
	private void flush() {
		if(! mBatch.isEmpty() ){
		mPool.execute(new PutContent(mContentSource.newSession() , mBatch) );
	     //new PutContent(mContentSource.newSession() , mBatch).run();
	     mBatch = null ;
		}
		log("main - finished flush");


	}
	private String getUri() {
		return mRoot + getRandom() + ".xml";
	}
	private String getRandom() {
		byte[] bytes = new byte[12];
		mRandom.nextBytes(bytes);
		
		return BSON.toHex(bytes);
		
		
	}
	private static void usage() {
		System.err
				.println("Usage: Importer [-input file] -connection conn [-root root] [-collection collection] [-threads n] [-batch n]");
		System.exit(1);

	}

	ContentSource getConnection(String connect) throws XccConfigException,
			Exception {
		URI serverUri = new URI(connect);
		ContentSource cs = ContentSourceFactory.newContentSource(serverUri,
				newTrustOptions(serverUri));
		return cs;
	}

	protected SecurityOptions newTrustOptions(URI uri) throws Exception {

		String scheme = uri.getScheme();
		if (!scheme.equals("xccs"))
			return null;

		TrustManager[] trust = new TrustManager[] {

		new X509TrustManager() {
			public void checkClientTrusted(X509Certificate[] x509Certificates,
					String s) throws CertificateException
			// nothing to do
			{

			}

			public void checkServerTrusted(X509Certificate[] x509Certificates,
					String s) throws CertificateException {
				// nothing to do
			}

			public X509Certificate[] getAcceptedIssuers() {
				return null;

			}
		} };

		SSLContext sslContext = SSLContext.getInstance("SSLv3");
		sslContext.init(null, trust, null);
		return new SecurityOptions(sslContext);

	}

}