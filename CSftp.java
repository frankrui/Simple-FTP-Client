

import java.lang.System;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

//
// This is an implementation of a simplified version of a command
// line ftp client. The program takes no arguments.
//


public class CSftp
{
    static final int MAX_LEN = 255;
    static Socket socket = new Socket();
    static BufferedReader ftpIn;
    static PrintWriter printWriter;
    
    public static void main(String [] args) {
        byte cmdString[] = new byte[MAX_LEN];
        try {
            for (int len = 1; len > 0;) {
                System.out.print("csftp> ");
                len = System.in.read(cmdString);
                if (len <= 0)
                    break;
                // Start processing the command here.
                String[] inputs = parseLine(cmdString);
                
                //Ignore empty line or line starting with #
                if ((inputs[0].equals("") || inputs[0].startsWith("#")) && inputs.length == 1) {
                    continue;
                }
                
                if (inputs[0].equals("open") && (inputs.length == 3 || inputs.length == 2) && (!socket.isConnected() || socket.isClosed())) {
                    String server = inputs[1];
                    int portNum = 21;
                    if (inputs.length == 2) {
                        server = removeNewLine(server.trim());
                    } else {
                        try {
                            inputs[2] = removeNewLine(inputs[2]);
                            portNum = Integer.parseInt(inputs[2]);
                        } catch (NumberFormatException e) {
                            System.out.println("802 Invalid argument"); //port number is not int
                            continue;
                        }
                    }
                    
                    try {
                        socket = new Socket(server,portNum);
                    } catch (IOException e) {
                        System.out.println("820 Control connection to " + server + " on port " + portNum + " failed to open.");
                        continue;
                    } catch (IllegalArgumentException i) {
                        System.out.println("820 Control connection to " + server + " on port " + portNum + " failed to open.");
                        continue;
                    }
                    try {
                        ftpIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        printWriter = new PrintWriter(socket.getOutputStream());
                    } catch (IOException io) {
                        System.out.println("825 Control connection I/O error, closing control connection.");
                        socket.close();
                        ftpIn.close();
                        printWriter.close();
                        continue;
                    }
                    
                    
                    handleMultiLineResponse();
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("open")) {
                    if (inputs.length != 3 && inputs.length != 2) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (!socket.isClosed()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                    
                }
                
                if (inputs[0].equals("user") && inputs.length == 2 && !socket.isClosed() && socket.isConnected()) {
                    String userName = "USER " + inputs[1];
                    send(userName);
                    handleMultiLineResponse();
                    
                    System.out.print("Please enter the password: ");
                    int userPasswordLength = System.in.read(cmdString);
                    if (userPasswordLength <= 0) {
                        break; //throw an error
                    }
                    String userPassword = "PASS " + removeNewLine(new String(cmdString, "UTF-8"));
                    send(userPassword);
                    handleMultiLineResponse();
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("user")) {
                    if (inputs.length != 2) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                if (inputs[0].equals("close") && !socket.isClosed() && inputs.length == 1 && socket.isConnected()) {
                    send("QUIT");
                    handleMultiLineResponse();
                    socket.close();
                    printWriter.close();
                    ftpIn.close();
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("close")) {
                    if (inputs.length != 1) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                if (inputs[0].equals("quit") && inputs.length == 1) {
                    if(socket.isConnected()) {
                        socket.close();
                        printWriter.close();
                        ftpIn.close();
                    }
                    System.out.println("GoodBye!");
                    break;
                } else if (inputs[0].equals("quit")) {
                    if (inputs.length != 1) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                }
                
                if (inputs[0].equals("get") && inputs.length == 2 && !socket.isClosed() && socket.isConnected()) {
                    String pathName = removeNewLine(inputs[1]);
                    
                    send("TYPE I");
                    String response = ftpIn.readLine();
                    if(response.startsWith("530 ")) {
                        System.out.println("<-- 803 Supplied command not expected at this time.");
                        continue;
                    } else
                        System.out.println("<-- " + response);
                    
                    send("SIZE " + pathName);
                    String result = ftpIn.readLine();
                    if (result.startsWith("550 ")) {
                        System.out.println("<-- 810 Access to local file " + pathName + " denied");
                        continue;
                    }
                    System.out.println("<-- " + result);
                    
                    String res[] = result.split(" ");
                    int size = Integer.parseInt(res[1]);
                    
                    send("PASV");
                    result = printAndReturnLastResponse();
                    
                    String[] results = result.split("\\(");
                    String Ip = getIp(results[1]);
                    int portNum = getPortNum(results[1]);
                    
                    Socket dataConnection = new Socket();
                    try {
                        dataConnection = new Socket(Ip, portNum);
                        
                        if(ftpIn.ready()) {
                            String code = ftpIn.readLine();
                            if (code.startsWith("425 ")) {
                                System.out.println("<-- 830 Data transfer connection to " + Ip + " on port " + portNum + " failed to open.");
                                continue;
                            }
                        }
                        
                        BufferedInputStream dataIn = new BufferedInputStream(dataConnection.getInputStream());
                        
                        send("RETR " + pathName);
                        response = ftpIn.readLine();
                        if (response.startsWith("450 ")) {
                            System.out.println("<-- 810 Access to local file " + pathName + " denied.");
                            continue;
                        } else
                            System.out.println("<-- " + response);
                        
                        byte readIn[] = new byte[size];
                        int read;
                        int offset = 0;
                        while ((read = dataIn.read(readIn, offset, readIn.length - offset)) != -1) {
                            offset += read;
                            if (readIn.length - offset == 0) {
                                break;
                            }
                        }
                        
                        //dataIn.read(readIn, 0, size);
                        
                        try {
                            File file = new File(pathName);
                            FileOutputStream fos = new FileOutputStream(file);
                            fos.write(readIn);
                            fos.close();
                        } catch (IOException io) {
                            System.out.println("Unable to write into file");
                        }
                        
                        dataConnection.close();
                        dataIn.close();
                        handleMultiLineResponse();
                    } catch (IOException io) {
                        System.out.println("835 Data transfer connection I/O error, closing data connection.");
                        dataConnection.close();
                    } catch (IllegalArgumentException e) {
                        System.out.println("830 Data transfer connection to " + Ip + " on port " + portNum + " failed to open.");
                    }
                    
                    cmdString = new byte[MAX_LEN];
                    continue;
                    
                } else if (inputs[0].equals("get")) {
                    if (inputs.length != 2) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                if (inputs[0].equals("put") && inputs.length == 2 && !socket.isClosed() && socket.isConnected()) {
                    String fileName = removeNewLine(inputs[1]);
                    File file = new File(fileName);
                    Socket dataConnection = new Socket();
                    String Ip = "";
                    int portNum = 0;
                    try {
                        FileInputStream fileIn = new FileInputStream(file);
                        int fileSize = (int) file.length();
                        byte content[] = new byte[fileSize];
                        fileIn.read(content, 0, fileSize);
                        
                        send("TYPE I");
                        String response = ftpIn.readLine();
                        if(response.startsWith("530 ")) {
                            System.out.println("<-- 803 Supplied command not expected at this time.");
                            continue;
                        } else
                            System.out.println("<-- " + response);
                        
                        
                        send("PASV");
                        String result = printAndReturnLastResponse();
                        
                        String[] results = result.split("\\(");
                        Ip = getIp(results[1]);
                        portNum = getPortNum(results[1]);
                        
                        dataConnection = new Socket(Ip, portNum);
                        if(ftpIn.ready()) {
                            String code = ftpIn.readLine();
                            if (code.startsWith("425 ")) {
                                System.out.println("<-- 830 Data transfer connection to " + Ip + " on port " + portNum + " failed to open.");
                                continue;
                            }
                        }
                        
                        BufferedOutputStream dataOut = new BufferedOutputStream(dataConnection.getOutputStream());
                        
                        send("STOR " + fileName);
                        handleMultiLineResponse();
                        
                        dataOut.write(content, 0, fileSize);
                        dataOut.flush();
                        
                        fileIn.close();
                        dataOut.close();
                        dataConnection.close();
                        
                        handleMultiLineResponse();
                        
                    } catch(FileNotFoundException e) {
                        System.out.println("810 Access to local file " + fileName + " denied.");
                    } catch(IOException io) {
                        System.out.println("835 Data transfer connection I/O error, closing data connection.");
                        dataConnection.close();
                    } catch(IllegalArgumentException i) {
                        System.out.println("830 Data transfer connection to " + Ip + " on port " + portNum + " failed to open.");
                    }
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("put")) {
                    if (inputs.length != 2) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                if (inputs[0].equals("cd") && inputs.length == 2 && !socket.isClosed() && socket.isConnected()) {
                    String directory = inputs[1];
                    send("CWD " + directory);
                    String response = ftpIn.readLine();
                    if(response.startsWith("530 ")) {
                        System.out.println("<-- 803 Supplied command not expected at this time.");
                        continue;
                    } else
                        System.out.println("<-- " + response);
                    
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("cd")) {
                    if (inputs.length != 2) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                if (inputs[0].startsWith("dir") && !socket.isClosed() && inputs.length == 1 && socket.isConnected()){
                    send("PASV");
                    String result = ftpIn.readLine();
                    if(result.startsWith("530 ")) {
                        System.out.println("<-- 803 Supplied command not expected at this time.");
                        continue;
                    } else
                        System.out.println("<-- " + result);
                    
                    String[] results = result.split("\\(");
                    String ip = getIp(results[1]);
                    int port = getPortNum(results[1]);
                    
                    Socket dataConnection = new Socket();
                    try {
                        dataConnection = new Socket(ip, port);
                        if(ftpIn.ready()) {
                            String code = ftpIn.readLine();
                            if (code.startsWith("425 ")) {
                                System.out.println("<-- 830 Data transfer connection to " + ip + " on port " + port + " failed to open.");
                                continue;
                            }
                        }
                        
                        send("LIST");
                        handleResponse();
                        
                        BufferedReader dataIn = new BufferedReader(new InputStreamReader(dataConnection.getInputStream()));
                        String line;
                        while((line = dataIn.readLine()) != null){
                            System.out.println(line);
                        }
                        dataConnection.close();
                        dataIn.close();
                        
                        handleResponse();
                        
                    } catch (IOException io) {
                        System.out.println("835 Data transfer connection I/O error, closing data connection.");
                        dataConnection.close();
                    } catch (IllegalArgumentException i) {
                        System.out.println("830 Data transfer connection to " + ip + " on port " + port + " failed to open.");
                    }
                    cmdString = new byte[MAX_LEN];
                    continue;
                } else if (inputs[0].equals("dir")) {
                    if (inputs.length != 1) {
                        System.out.println("801 Incorrect number of arguments");
                        continue;
                    }
                    if (socket.isClosed() || !socket.isConnected()) {
                        System.out.println("803 Supplied command not expected at this time.");
                        continue;
                    }
                }
                
                
                
                System.out.println("800 Invalid Command");   //print if didn't match any of the ifs
            }
        } catch (IOException exception) {
            System.err.println("898 Input error while reading commands, terminating. " + exception.getMessage());
        } catch (Exception e) {
            System.err.println("899 Processing error. " + e.getMessage());
        }
    }
    
    public static String[] parseLine(byte[] cmdString) {
        String cmd = "";
        try {
            cmd = new String(cmdString, "UTF-8");
            cmd = removeNewLine(cmd);
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        String[] parts = cmd.split(" ");
        return parts;
    }
    
    public static String removeNewLine(String input) {
        return input.split("(\\r)?\\n")[0];
    }
    
    public static String getIp(String input) {
        String values[] = new String[10];
        
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(input);
        int i = 0;
        while (matcher.find()) {
            values[i] = (matcher.group());
            i++;
        }
        return values[0] + "." + values[1] + "." + values[2] + "." + values[3];
    }
    
    public static int getPortNum(String input) {
        int portNum;
        
        String values[] = new String[10];
        
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(input);
        int i = 0;
        while (matcher.find()) {
            values[i] = (matcher.group());
            i++;
        }
        portNum = Integer.parseInt(values[4]) * 256 + Integer.parseInt(values[5]);
        return portNum;
    }
    
    public static void send(String command) {
        
        printWriter.print(command+"\r\n");
        printWriter.flush();
        System.out.println("--> " + command);
    }
    
    public static void handleResponse() {
        try {
            String result = ftpIn.readLine();
            
            result = result.replaceFirst("-", " ");
            String results[] = result.split(" ");
            int errorCode = Integer.parseInt(results[0]);
            switch (errorCode) {
                case 503: result = "802 Invalid Argument";
                case 501: result = "802 Invalid Argument";
            }
            System.out.println("<-- " + result);
            
            
        } catch (IOException e) {
            System.out.println("825 Control connection I/O error, closing control connection");
            try {
                socket.close();
                ftpIn.close();
                printWriter.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }
    
    private static void handleMultiLineResponse() {
        try {
            String result;
            while (!(result = ftpIn.readLine()).matches("\\d\\d\\d\\s.*")) {
                System.out.println(result);			   
            }
            System.out.println("<-- " + result);
        } catch (IOException e) {
            System.out.println("825 Control connection I/O error, closing control connection");
            try {
                socket.close();
                ftpIn.close();
                printWriter.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }
    
    private static String printAndReturnLastResponse() {
    	String result = "";
        try {
        	
            while (!(result = ftpIn.readLine()).matches("\\d\\d\\d\\s.*")) {
                System.out.println("<-- " + result);			   
            }
            System.out.println("<-- " + result);
            
        } catch (IOException e) {
            System.out.println("825 Control connection I/O error, closing control connection");
            try {
                socket.close();
                ftpIn.close();
                printWriter.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        return result;
    }
    
}