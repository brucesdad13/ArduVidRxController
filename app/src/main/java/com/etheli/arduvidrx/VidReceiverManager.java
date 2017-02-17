//VidReceiverManager.java:  Manages command I/O for an ArduVidRx unit.
//
// 12/30/2016 -- [ET]
//

package com.etheli.arduvidrx;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import com.etheli.util.PausableWorker;
import java.util.Vector;

/**
 * Class VidReceiverManager manages command I/O for an ArduVidRx unit.
 */
public class VidReceiverManager
{
    /** Delay (ms) to be executed between update intervals. */
  public static final int UPDWKR_PERIODIC_DELAYMS = 100;
    /** Size of received-line buffer. */
  public static final int BUFF_MAX_LINES = 100;
    /** Length of time to wait for expected responses from receiver. */
  public static final int RESP_WAIT_TIMEMS = 250;
    /** Tag string for debug logging. */
  public static final String LOG_TAG = "VidRecMgr";

  public static final String VIDRX_CR_STR = "\r";
  public static final byte [] VIDRX_CR_ARR = VIDRX_CR_STR.getBytes();
  public static final String VIDRX_SPACECR_STR = " \r\r";
  public static final byte [] VIDRX_SPACECR_ARR = VIDRX_SPACECR_STR.getBytes();
  public static final byte [] VIDRX_RESET_CMD = ("XZ"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_VERSION_CMD = ("V"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_ECHOOFF_CMD = ("E0"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_ECHOON_CMD = ("E1"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_READRSSI_CMD = ("XR"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_UPONEMHZ_CMD = ("U"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_DOWNONEMHZ_CMD = ("D"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_AUTOTUNE_CMD = ("A"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_NEXTFREQ_CMD = ("N"+VIDRX_CR_STR).getBytes();
  public static final byte [] VIDRX_PREVFREQ_CMD = ("P"+VIDRX_CR_STR).getBytes();
  public static final String VIDRX_TUNE_PRESTR = "T";
  public static final String VIDRX_MINRSSI_PRESTR = "XM";

  private static final int VIDCMD_TERMINATE_MSGC = 0;      //codes for command messages
  private static final int VIDCMD_TUNECODE_MSGC = 1;       // via CommandHandlerThread
  private static final int VIDCMD_TUNEFREQ_MSGC = 2;
  private static final int VIDCMD_UPONEMHZ_MSGC = 3;
  private static final int VIDCMD_DOWNONEMHZ_MSGC = 4;
  private static final int VIDCMD_AUTOTUNE_MSGC = 5;
  private static final int VIDCMD_NEXTCHAN_MSGC = 6;
  private static final int VIDCMD_PREVCHAN_MSGC = 7;

  private final Activity parentActivityObj;
  private final BluetoothSerialService bluetoothSerialServiceObj;
  private final StringBuffer receivedCharsBuffer = new StringBuffer();
  private final Vector<String> receivedLinesList = new Vector<String>();
  private char firstReceivedCharacter = '\0';
  private ChannelTracker vidChannelTrackerObj = null;
  private Handler mainGuiUpdateHandlerObj = null;
  private char receivedLinesLastEndChar = '\0';
  private ReceiverUpdateWorker receiverUpdateWorkerObj = null;
  private CommandHandlerThread commandHandlerThreadObj = new CommandHandlerThread();
  private int minRssiForScansValue = 30;


  /**
   * Creates an ArduVidRx manager.
   * @param activityObj parent object for UI Activity Context.
   * @param serviceObj BluetoothSerialService object to use for sending commands.
   */
  public VidReceiverManager(Activity activityObj, BluetoothSerialService serviceObj)
  {
    parentActivityObj = activityObj;
    bluetoothSerialServiceObj = serviceObj;
  }

  /**
   * Sets the channel-tracker object.
   * @param chTrackerObj tracker object for currently-tuned video-receiver channel.
   */
  public void setChannelTrackerObj(ChannelTracker chTrackerObj)
  {
    vidChannelTrackerObj = chTrackerObj;
  }

  /**
   * Sets the GUI-update handler object.
   * @param updHandlerObj handler for update messages to main GUI.
   */
  public void setGuiUpdateHandlerObj(Handler updHandlerObj)
  {
    mainGuiUpdateHandlerObj = updHandlerObj;
  }

  /**
   * Starts the video-receiver-manager threads.
   */
  public void startManager()
  {
    (new Thread("vidRecMgrStartup")
        {
          public void run()
          {
            doManagerStartup();
          }
        }).start();
  }

  /**
   * Performs the work of starting the video-receiver-manager threads.
   */
  private void doManagerStartup()
  {
    try
    {
      try { Thread.sleep(250); }            //start with delay in case receiver
      catch(InterruptedException ex) {}     // needs some setup time

      Log.d(LOG_TAG, "Began manager startup");

         //start with " <CR><CR>" in case monitor mode is in progress
         // (prepend space to make sure isn't command-repeat via <Enter>):
      outputCmdNoResponse(VIDRX_SPACECR_ARR);
      try { Thread.sleep(100); }            //bit of delay in case receiver
      catch(InterruptedException ex) {}     // sends unexpected output

      final String versionStr;
      if((versionStr=queryGetVersionInfo()) != null && versionStr.trim().length() > 0)
      {  //non-empty version string successfully fetched from receiver
        Log.d(LOG_TAG, "Receiver version info:  " + versionStr);
        if(mainGuiUpdateHandlerObj != null)
        {
          mainGuiUpdateHandlerObj.obtainMessage(
                                 ProgramResources.MAINGUI_UPD_VERSION,versionStr).sendToTarget();
        }
        getNextReceivedLine(RESP_WAIT_TIMEMS);   //receive and discard 2nd line of response
      }
      else
      {  //unable to fetch version string from receiver
        final String popStr = parentActivityObj.getString(R.string.errfetch_version_info);
        Log.e(LOG_TAG,popStr);
        if(mainGuiUpdateHandlerObj != null)
        {
          mainGuiUpdateHandlerObj.obtainMessage(
                                    ProgramResources.MAINGUI_UPD_POPUPMSG,popStr).sendToTarget();
        }
      }
      outputReceiverEchoOffCommand();       //send echo-off command

      if(!queryReportChanRssiVals())   //do initial query/report of channel/RSSI values
      {  //query failed
        final String popStr = parentActivityObj.getString(R.string.errfetch_chanrssi_vals);
        Log.e(LOG_TAG,popStr);
        if(mainGuiUpdateHandlerObj != null)
        {
          mainGuiUpdateHandlerObj.obtainMessage(      //show popup with error message
                                    ProgramResources.MAINGUI_UPD_POPUPMSG,popStr).sendToTarget();
        }
      }

      fetchMinRssiValFromReceiver();        //get/save min-RSSI-for-scans value from receiver

      try { Thread.sleep(UPDWKR_PERIODIC_DELAYMS); }
      catch(InterruptedException ex) {}     //do one interval delay before starting worker
      if(receiverUpdateWorkerObj != null)        //if previous worker then
        receiverUpdateWorkerObj.terminate();     //stop it
      Log.d(LOG_TAG, "Starting receiver-update worker");
      receiverUpdateWorkerObj = new ReceiverUpdateWorker();
      receiverUpdateWorkerObj.start();           //start update worker
      if(commandHandlerThreadObj.wasStartedFlag)                //if was previously run then
        commandHandlerThreadObj = new CommandHandlerThread();   //create new thread object
      commandHandlerThreadObj.start();           //start command handler
      if(mainGuiUpdateHandlerObj != null)
      {
        mainGuiUpdateHandlerObj.obtainMessage(   //send notification that manager startup is done
                                        ProgramResources.MAINGUI_UPD_VRMGRSTARTED).sendToTarget();
      }
      Log.d(LOG_TAG, "Finished manager startup");
    }
    catch(Exception ex)
    {
      Log.e(LOG_TAG, "Exception during manager startup", ex);
    }
  }

  /**
   * Stops the video-receiver-manager threads.
   */
  public void stopManager()
  {
    try
    {
      commandHandlerThreadObj.sendMessage(VIDCMD_TERMINATE_MSGC);    //stop command handler
      if(receiverUpdateWorkerObj != null)
      {  //worker was created; stop it
        receiverUpdateWorkerObj.terminate();
        receiverUpdateWorkerObj = null;
      }
    }
    catch(Exception ex)
    {  //some kind of exception error
      Log.e(LOG_TAG, "Error stopping video-receiver-manager threads", ex);
    }
  }

  /**
   * Sends command to tune receiver to given channel-code value (i.e., "F4").
   * @param chanCodeStr channel-code value (i.e., "F4").
   */
  public void tuneReceiverToChannelCode(String chanCodeStr)
  {
    commandHandlerThreadObj.sendMessage(VIDCMD_TUNECODE_MSGC,chanCodeStr);
  }

  /**
   * Sends command to tune receiver to given frequency value (in MHz).
   * @param freqVal frequency value (in MHz).
   */
  public void tuneReceiverToFrequency(int freqVal)
  {
    commandHandlerThreadObj.sendMessage(VIDCMD_TUNECODE_MSGC,Integer.toString(freqVal));
  }

  /**
   * Sends command to tune receiver frequency up or down by one MHz.
   * @param upFlag true for +1 MHz; false for -1 MHz.
   */
  public void tuneReceiverFreqByOneMhz(boolean upFlag)
  {
    commandHandlerThreadObj.sendMessage(upFlag ? VIDCMD_UPONEMHZ_MSGC : VIDCMD_DOWNONEMHZ_MSGC);
  }

  /**
   * Sends command to auto-tune receiver to strongest channel.
   */
  public void autoTuneReceiver()
  {
    commandHandlerThreadObj.sendMessage(VIDCMD_AUTOTUNE_MSGC);
  }

  /**
   * Selects the next (or previous) tuner channel from amongst those that
   * have a signal on them.
   * @param nextFlag true for next; false for previous.
   */
  public void selectPrevNextReceiverChannel(boolean nextFlag)
  {
    commandHandlerThreadObj.sendMessage(nextFlag ? VIDCMD_NEXTCHAN_MSGC : VIDCMD_PREVCHAN_MSGC);
  }

  /**
   * Pauses the receiver-update worker (so commands can be sent to the receiver).
   */
  public void pauseReceiverUpdateWorker()
  {
    if(receiverUpdateWorkerObj != null)
      receiverUpdateWorkerObj.pauseThread(1000);
  }

  /**
   * Resumes the receiver-update worker.
   */
  public void resumeReceiverUpdateWorker()
  {
    if(receiverUpdateWorkerObj != null)
      receiverUpdateWorkerObj.resumeThread();
  }

  /**
   * Receives and processes command messages via CommandHandlerThread.
   * @param msgObj handler-message object.
   */
  private void handleReceiverCommandMessage(Message msgObj)
  {
    switch(msgObj.what)
    {
      case VIDCMD_TUNECODE_MSGC:       //tune receiver to channel code
      case VIDCMD_TUNEFREQ_MSGC:       //tune receiver to frequency in MHz
        sendCommandNoResponse(VIDRX_TUNE_PRESTR + msgObj.obj + VIDRX_CR_STR);
        break;
      case VIDCMD_UPONEMHZ_MSGC:       //tune receiver frequency up by one MHz
        sendCommandNoResponse(VIDRX_UPONEMHZ_CMD);
        break;
      case VIDCMD_DOWNONEMHZ_MSGC:     //tune receiver frequency down by one MHz
        sendCommandNoResponse(VIDRX_DOWNONEMHZ_CMD);
        break;
      case VIDCMD_AUTOTUNE_MSGC:       //auto-tune receiver to strongest channel
        doAutoTuneReceiver();
        break;
      case VIDCMD_NEXTCHAN_MSGC:       //select next channel
        doSelectNextPrevReceiverChannel(VIDRX_NEXTFREQ_CMD);
        break;
      case VIDCMD_PREVCHAN_MSGC:       //select previous channel
        doSelectNextPrevReceiverChannel(VIDRX_PREVFREQ_CMD);
        break;
      case VIDCMD_TERMINATE_MSGC:      //terminate thread/looper for command handler
        commandHandlerThreadObj.quitThreadLooper();
        break;
    }
  }

  /**
   * Sends given command and returns "echo" characters (if any).
   * @param cmdBuff command to be sent.
   * @return Received "echo" characters (if any), or null if timeout reached
   * before receiving an end-of-line character.
   */
  protected String sendCommandNoResponse(byte [] cmdBuff)
  {
    pauseReceiverUpdateWorker();
    final String retStr = outputCmdNoResponse(cmdBuff);
    resumeReceiverUpdateWorker();
    return retStr;
  }

  /**
   * Sends given command and returns "echo" characters (if any).
   * @param cmdStr command to be sent.
   * @return Received "echo" characters (if any), or null if timeout reached
   * before receiving an end-of-line character.
   */
  protected String sendCommandNoResponse(String cmdStr)
  {
    pauseReceiverUpdateWorker();
    final String retStr = outputCmdNoResponse(cmdStr.getBytes());
    resumeReceiverUpdateWorker();
    return retStr;
  }

  /**
   * Sends given command and returns received response line.
   * @param cmdBuff command to be sent.
   * @return Received response line, or null if timeout reached
   * before receiving response.
   */
  protected String sendCommandRecvResponse(byte [] cmdBuff)
  {
    pauseReceiverUpdateWorker();
    final String retStr = outputCmdRecvResponse(cmdBuff);
    resumeReceiverUpdateWorker();
    return retStr;
  }

  /**
   * Sends given command and returns received response line.
   * @param cmdStr command to be sent.
   * @return Received response line, or null if timeout reached
   * before receiving response.
   */
  protected String sendCommandRecvResponse(String cmdStr)
  {
    pauseReceiverUpdateWorker();
    final String retStr = outputCmdRecvResponse(cmdStr.getBytes());
    resumeReceiverUpdateWorker();
    return retStr;
  }

  /**
   * Performs the work of sending the command to auto-tune receiver to strongest channel.
   */
  private void doAutoTuneReceiver()
  {
    pauseReceiverUpdateWorker();
    if(outputCmdNoResponse(VIDRX_AUTOTUNE_CMD) != null)    //send 'A' command
    {  //initial newline response received OK
      processReceiverScanning();                 //wait for scanning to finish
    }
    resumeReceiverUpdateWorker();
  }

  /**
   * Performs the work of selecting the next (or previous) tuner channel
   * from amongst those that have a signal on them.
   * @param cmdBuff command to be sent.
   */
  public void doSelectNextPrevReceiverChannel(byte [] cmdBuff)
  {
    pauseReceiverUpdateWorker();
    if(outputCmdNoResponse(cmdBuff) != null)     //send 'N' or 'P' command
    {  //initial newline response received OK
      if(peekFirstReceivedChar() == ' ')         //if receiver is scanning then
        processReceiverScanning();               //wait for scanning to finish
    }
    resumeReceiverUpdateWorker();
  }

  /**
   * Notifies OperationFragment that receiver is scanning and waits for
   * scanning to finish.
   */
  private void processReceiverScanning()
  {
    if(mainGuiUpdateHandlerObj != null)
    {  //handler OK; notify OperationFragment that receiver scanning has started
      mainGuiUpdateHandlerObj.obtainMessage(
                                          ProgramResources.MAINGUI_UPD_SCANBEGIN).sendToTarget();
    }
    getNextReceivedLine(8000);     //wait for response (allow for scanning time)
    if(mainGuiUpdateHandlerObj != null)
    {  //handler OK; notify OperationFragment that receiver scanning is finished
      mainGuiUpdateHandlerObj.obtainMessage(ProgramResources.MAINGUI_UPD_SCANEND).sendToTarget();
    }
  }

  /**
   * Outputs a new min-RSSI-for-scans value to the receiver.
   * This method should only be used while the receiver worker
   * is stopped or paused.
   */
  private void outputMinRssiValToReceiver(int minRssiVal)
  {
    pauseReceiverUpdateWorker();
    outputCmdNoResponse((VIDRX_MINRSSI_PRESTR + minRssiVal + VIDRX_CR_STR).getBytes());
    if(fetchMinRssiValFromReceiver() != minRssiVal)   //read back and check value
      Log.e(LOG_TAG, "Mismatch confirming min-RSSI value sent to receiver");
    resumeReceiverUpdateWorker();
  }

  /**
   * Sends the reset command to the receiver.  This method should only be
   * used while the receiver worker is stopped or paused.
   */
  public void outputReceiverResetCommand()
  {
    clearBuffer();
    bluetoothSerialServiceObj.write(VIDRX_RESET_CMD);
  }

  /**
   * Sends the echo-off command to the receiver.  This method should only be
   * used while the receiver worker is stopped or paused.
   * @return Received "echo" characters (if any), or null if timeout reached
   * before receiving an end-of-line character.
   */
  public String outputReceiverEchoOffCommand()
  {
    return outputCmdNoResponse(VIDRX_ECHOOFF_CMD);
  }

  /**
   * Fetches and saves the min-RSSI-for-scans value from the receiver.
   * This method should only be used while the receiver worker is
   * stopped or paused.
   * @return The fetched value.
   */
  public int fetchMinRssiValFromReceiver()
  {
    try
    {         //send command, receive and save numeric response
      minRssiForScansValue = outputCmdRecvIntResp(
                                                 (VIDRX_MINRSSI_PRESTR+VIDRX_CR_STR).getBytes());
    }
    catch(Exception ex)
    {
      Log.e(LOG_TAG, "Error fetching min-RSSI value from receiver", ex);
    }
    return minRssiForScansValue;
  }

  /**
   * Queries, receives and returns program-version information for receiver.
   * This method should only be used while the receiver worker is stopped
   * or paused.
   * @return Program-version-information string, or null if unable to receive.
   */
  private String queryGetVersionInfo()
  {
    return outputCmdRecvResponse(VIDRX_VERSION_CMD);
  }

  /**
   * Sends given command and returns received numeric response.  This
   * method should only be used while the receiver worker is stopped
   * or paused.
   * @param cmdBuff command to be sent.
   * @return Received response value.
   * @throws NumberFormatException If the received response could not be
   * parsed as an integer, or if no response was received.
   */
  protected int outputCmdRecvIntResp(byte [] cmdBuff) throws NumberFormatException
  {
    final String str;
    if((str=outputCmdRecvResponse(cmdBuff)) != null)
    {  //received response; attempt to parse as number
      return Integer.parseInt(str.trim());
    }
    throw new NumberFormatException(parentActivityObj.getString(R.string.errfetch_response));
  }

  /**
   * Outputs given command and returns "echo" characters (if any).
   * (Lower-level I/O).
   * @param cmdBuff command to be sent.
   * @return Received "echo" characters (if any), or null if timeout reached
   * before receiving an end-of-line character.
   */
  private String outputCmdNoResponse(byte [] cmdBuff)
  {
    try
    {
      clearBuffer();
      bluetoothSerialServiceObj.write(cmdBuff);
      return getNextReceivedLine(RESP_WAIT_TIMEMS);
    }
    catch(Exception ex)
    {
      Log.e(LOG_TAG, "Error sending command to receiver", ex);
      return null;
    }
  }

  /**
   * Outputs given command and returns received response line.
   * (Lower-level I/O).
   * @param cmdBuff command to be sent.
   * @return Received response line, or null if timeout reached
   * before receiving response.
   */
  private String outputCmdRecvResponse(byte [] cmdBuff)
  {
    return outputCmdRecvResponse(cmdBuff,RESP_WAIT_TIMEMS);
  }

  /**
   * Outputs given command and returns received response line.
   * (Lower-level I/O).
   * @param cmdBuff command to be sent.
   * @param timeoutMs maximum number of milliseconds to wait.
   * @return Received response line, or null if timeout reached
   * before receiving response.
   */
  private String outputCmdRecvResponse(byte [] cmdBuff, int timeoutMs)
  {
    if(outputCmdNoResponse(cmdBuff) == null)
      return null;
    return getNextReceivedLine(timeoutMs);
  }

  /**
   * Clears any received lines in the buffer.
   */
  private void clearBuffer()
  {
    synchronized(receivedLinesList)
    {  //grab thread lock for list
      receivedLinesList.clear();             //clear lines list
    }
  }

  /**
   * Stores the received characters.
   * @param buff buffer of received characters.
   * @param numChars number of characters in buffer.
   */
  public void storeReceivedChars(byte [] buff, int numChars)
  {
    char ch;
    for(int i=0; i<numChars; ++i)
    {  //for each character received
      if((ch=(char)buff[i]) == '\r' || ch == '\n')
      {  //end of line; add received line to list
        if(receivedCharsBuffer.length() > 0 || ch == receivedLinesLastEndChar ||
                                                                receivedLinesLastEndChar == '\0')
        {  //character is not second char of CR/LF sequence
          synchronized(receivedLinesList)
          {  //grab thread lock for list
            receivedLinesList.add(receivedCharsBuffer.toString());
            receivedCharsBuffer.setLength(0);    //clear character buffer
            if(receivedLinesList.size() > BUFF_MAX_LINES)  //if too many lines then
              receivedLinesList.remove(0);                 //remove oldest
            receivedLinesList.notifyAll();       //wake 'get' method if waiting
            firstReceivedCharacter = '\0';       //clear first-received character
          }
          receivedLinesLastEndChar = ch;         //track last CR/LF character
        }
      }
      else
      {  //not end of line
        receivedCharsBuffer.append(ch);          //add to character buffer
        if(firstReceivedCharacter == '\0')       //if no previous for line then
          firstReceivedCharacter = ch;           //save first-received character
      }
    }
  }

  /**
   * Waits for and returns next line of received characters, up to the timeout.
   * @param timeoutMs maximum number of milliseconds to wait.
   * @return A string containing the next received line, or null if timeout.
   */
  private String getNextReceivedLine(int timeoutMs)
  {
    synchronized(receivedLinesList)
    {  //grab thread lock for list
      if(receivedLinesList.size() > 0)
      {  //list not empty; fetch and return received line
        final String str = receivedLinesList.remove(0);    //remove line from list
        return str;
      }
      try
      {       //wait for line of data received; up to timeout
        receivedLinesList.wait(timeoutMs);
      }
      catch(InterruptedException ex)
      {
      }
      if(receivedLinesList.size() <= 0)     //if line not received then
        return null;                        //indicate no data
              //fetch and return received line:
      final String str = receivedLinesList.remove(0);      //remove line from list
      return str;
    }
  }

  /**
   * Waits for and returns the first character received from the video
   * receiver (waits up to 1 second).
   * @return The received character, or '\0' if none received.
   */
  private char peekFirstReceivedChar()
  {
    int count = 0;
    while(true)
    {  //loop while waiting for character to arrive
      if(firstReceivedCharacter != '\0')    //if character received then
        return firstReceivedCharacter;      //return character
      if(count % 10 == 0)
      {  //check if full line received (but not every loop)
        synchronized(receivedLinesList)
        {  //grab thread lock for list
          if(receivedLinesList.size() > 0)
          {  //line of characters was received
            final String str = receivedLinesList.get(0);        //get line from list
            return (str.length() > 0) ? str.charAt(0) : '\n';   //return first char
          }
        }
      }
      if(++count > 99)       //increment loop count
        break;               //if too many then abort
      try
      {       //wait 10ms each loop
        Thread.sleep(10);
      }
      catch(InterruptedException ex)
      {
      }
    }
    return '\0';        //timeout reached
  }

  /**
   * Queries, receives and reports the channel and RSSI values from the receiver.
   * @return true if successful; false if error.
   */
  private boolean queryReportChanRssiVals()
  {
    String respStr;
    if((respStr=outputCmdRecvResponse(VIDRX_READRSSI_CMD)) != null)
    {  //command sent and response received OK
      try
      {       //parse "freqCC=rssi" response into two integers:
        respStr = respStr.trim();           //remove leading space
        final int p;
        if((p=respStr.indexOf('=')) > 3)
        {  //length of response OK
          final int freqVal = Integer.parseInt(respStr.substring(0,4));
          final String chCodeStr = (p >= 6) ? respStr.substring(4,6) : null;
          final int rssiVal = Integer.parseInt(respStr.substring(p+1));
              //send update to channel tracker:
          if(vidChannelTrackerObj != null)
            vidChannelTrackerObj.setFreqChannel(chCodeStr,(short)freqVal);
              //report values to main GUI:
          if(mainGuiUpdateHandlerObj != null)
          {
            mainGuiUpdateHandlerObj.obtainMessage(
                           ProgramResources.MAINGUI_UPD_CHANRSSI,freqVal,rssiVal).sendToTarget();
          }
          return true;       //indicate success
        }
      }
      catch(NumberFormatException ex)
      {  //error parsing; return false for error
      }
    }
    return false;            //indicate failure
  }


  /**
   * Class ReceiverUpdateWorker defines a background-worker thread for
   * reading data from the receiver.
   */
  private class ReceiverUpdateWorker extends PausableWorker
  {
    /**
     * Creates background-worker thread for reading data from the receiver.
     */
    public ReceiverUpdateWorker()
    {
      super("VidReceiverUpdateWorker",UPDWKR_PERIODIC_DELAYMS);
    }

  /**
   * Receiver-update task method to be invoked at each interval.
   * @return true if the worker should continue running;
   * false if the worker should terminate.
   */
    @Override
    public boolean doWorkerTask()
    {
      if(!queryReportChanRssiVals())                  //do query and report
        waitForNotify(UPDWKR_PERIODIC_DELAYMS*2);     //if failure then extra delay
      return true;
    }
  }

  /**
   * Class CommandHandlerThread handles commands for the video receiver
   * via a separate Looper thread.
   */
  private class CommandHandlerThread extends Thread
  {
    private boolean wasStartedFlag = false;
    private Handler looperHandlerObj = null;
    private Looper threadLoopObj = null;

    /**
     * Creates a command-handling thread.
     */
    public CommandHandlerThread()
    {
      super("CommandHandlerThread");
    }

    /**
     * Sets up and runs the looper for message handling.
     */
    public void run()
    {
      wasStartedFlag = true;
      Looper.prepare();
      looperHandlerObj = new Handler()
          {        //pass messages to parent method
            @Override
            public void handleMessage(Message msgObj)
            {
              try
              {
                handleReceiverCommandMessage(msgObj);
              }
              catch(Exception ex)
              {
                Log.e(LOG_TAG, "Error handling command message", ex);
              }
            }
          };
      threadLoopObj = Looper.myLooper();    //save handle to looper obj
      Looper.loop();         //run message-handling loop
    }

    /**
     * Determines if the thread was previously started.
     * @return true if the thread was previously started; false if not.
     */
    public boolean wasStarted()
    {
      return wasStartedFlag;
    }

    /**
     * Terminates the thread/looper.
     */
    public void quitThreadLooper()
    {
      if(threadLoopObj != null)
        threadLoopObj.quit();
    }

    /**
     * Sends command message to the handler.
     * @param msgCode message code (one of the "VIDCMD_..." values).
     */
    public void sendMessage(int msgCode)
    {
      if(looperHandlerObj != null)
        looperHandlerObj.obtainMessage(msgCode).sendToTarget();
    }

    /**
     * Sends command message to the handler.
     * @param msgCode message code (one of the "VIDCMD_..." values).
     * @param paramStr parameter string for command.
     */
    public void sendMessage(int msgCode, String paramStr)
    {
      if(looperHandlerObj != null)
        looperHandlerObj.obtainMessage(msgCode,paramStr).sendToTarget();
    }
  }
}