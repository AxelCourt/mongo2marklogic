/**
 * Copyright 2003-2013 MarkLogic Corporation
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *    Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */
package com.marklogic.mongo;

import java.util.Random;

import javax.xml.stream.XMLStreamException;

import com.marklogic.xcc.exceptions.XccConfigException;

public abstract class Importer
{

    private Random mRandom = new Random();

    protected static int totalFiles = 0;

    protected XMLWriter mWriter;

    protected boolean bUseId = false  ;
    
    
    synchronized static void completed( int files  )
    {
        totalFiles += files ;
    }
    
    
    abstract void run(String[] args) throws XccConfigException, Exception;


    protected synchronized void log(String string)
    {
    	System.out.println(string);
    	
    }


    synchronized void log(String string, Exception e)
    {
    	
    	System.out.println(string);
    	e.printStackTrace();
    }


    protected String getRandom()
    {
    	byte[] bytes = new byte[12];
    	mRandom.nextBytes(bytes);
    	
    	return BSON.toHex(bytes);
    	
    	
    }


    protected XMLWriter getWriter(String[] args) throws XMLStreamException
    {
        String type = getArg(args ,"writer","json");
        if( type.equals("bson"))
           return new BSONXMLWriter();
        else
            return new JSONXMLWriter();
    }


    /**
     * @param args
     * @throws Exception 
     * @throws XccConfigException 
     */
    public static void main(String[] args) throws XccConfigException, Exception
    {
        

        long tm_start = System.currentTimeMillis();
        
        if( hasArg(args,"directory"))
            new FileImporter().run(args);
        else
        if(hasArg(args,"connection"))
            new XCCImporter().run(args);
        else 
            usage();
        
        long tm_stop = System.currentTimeMillis();
        long ms = tm_stop - tm_start ; 
        
        
      
        System.out.println("Total docs:  " + totalFiles + " total time: " + String.valueOf(  ((double)ms) /1000. ) +  " = " +
                ((double)totalFiles / (ms/1000.)) + " docs/sec" );

    }

    static boolean hasArg(String[] args, String name)
    {
    
    	for(int i = 0 ; i < args.length ; i++ ){
    		if( args[i].startsWith("-")){
    			if( args[i].substring(1).equals(name) )
    				return true;
    		}
    	}
    	return false ;
    	
    }

    protected static String getArg(String[] args, String name, String def)
    {
    
    	for(int i = 0 ; i < args.length-1 ; i++ ){
    		if( args[i].startsWith("-")){
    			if( args[i].substring(1).equals(name) )
    				return args[i+1];
    		}
    	}
    	return def ;
    	
    }


    private static void usage()
    {
    	System.err
    			.println("Usage: Importer [-input file] -connection conn [-root root] [-collection collection] [-threads n] [-batch n]  [-writer json|bson]");
    	System.err 
                .println("       Importer [-input file] -directory output [-writer json|bson]");
    	System.exit(1);
    
    }

}
