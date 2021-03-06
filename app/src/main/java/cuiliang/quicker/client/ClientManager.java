package cuiliang.quicker.client;

import android.os.Build;
import android.util.Base64;
import android.util.Log;

import cuiliang.quicker.BuildConfig;
import cuiliang.quicker.events.ConnectionStatusChangedEvent;
import cuiliang.quicker.events.ServerMessageEvent;
import cuiliang.quicker.events.SessionClosedEvent;
import cuiliang.quicker.messages.MessageBase;
import cuiliang.quicker.messages.recv.LoginStateMessage;
import cuiliang.quicker.messages.send.ButtonClickedMessage;
import cuiliang.quicker.messages.send.CommandMessage;
import cuiliang.quicker.messages.send.DeviceLoginMessage;
import cuiliang.quicker.messages.send.PhotoMessage;
import cuiliang.quicker.messages.send.TextDataMessage;
import cuiliang.quicker.messages.send.ToggleMuteMessage;
import cuiliang.quicker.messages.send.UpdateVolumeMessage;
import cuiliang.quicker.network.MyCodecFactory;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.net.InetSocketAddress;


/**
 * 客户端调度类
 */
public class ClientManager {
    private static final String TAG = ClientManager.class.getSimpleName();

    private ClientConfig _config;


    private IoSession _session;

    private NioSocketConnector _connector;

    private Thread _connectThread;


    // 连接状态
    private ConnectionStatus _status = ConnectionStatus.Disconnected;




    public ClientManager(ClientConfig config) {
        _config = config;

        EventBus.getDefault().register(this);
    }

    // 更新配置
    public void setConfig(ClientConfig config) {
        _config = config;
    }


    public boolean isConnected() {
        return (_session != null && _session.isConnected());
    }

    public ConnectionStatus getConnectionStatus() {

        return _status;
    }

    public void sendMessage(MessageBase msg) {
        if (isConnected()) {
            _session.write(msg);
        } else {

            Log.w(TAG, "未连接服务器，所以无法发送！");
        }
    }

    /**
     * 建立连接
     * @param retry
     */
    public void connect(final int retry) {
        this.shutdown();
        _connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                doConnect(retry);
            }
        });

        _connectThread.start();
    }

    /**
     * 执行建立链接
     * @param retryCount 重试次数
     */
    private void doConnect(int retryCount) {


        if (_status == ConnectionStatus.Connecting){
            Log.e(TAG, "正在连接服务器，不能重复连接。");
        }

        _connector = new NioSocketConnector();
        _connector.setConnectTimeoutMillis(3000);

        //设置协议封装解析处理
        _connector.getFilterChain().addLast("protocol", new ProtocolCodecFilter(new MyCodecFactory()));

//        //设置心跳包
//        KeepAliveFilter heartFilter = new KeepAliveFilter(new HeartbeatMessageFactory());
//        heartFilter.setRequestInterval(5 * 60);
//        heartFilter.setRequestTimeout(10);
//        mSocketConnector.getFilterChain().addLast("heartbeat", heartFilter);

        //设置 handler 处理业务逻辑
        _connector.setHandler(new MinaClientHandler());
        InetSocketAddress mSocketAddress = new InetSocketAddress(_config.mServerHost, _config.mServerPort);

        //配置服务器地址
        int count = 0;
        do{

            try {

                if (count > 0){
                    Thread.sleep(2000);
                }



                Log.d(TAG, "开始连接服务器...");
                changeStatus(ConnectionStatus.Connecting, "");

                ConnectFuture mFuture = _connector.connect(mSocketAddress);
                mFuture.awaitUninterruptibly();

                if (!mFuture.isConnected()){
                    Throwable e = mFuture.getException();
                    Log.e(TAG, "链接失败" + e.getMessage());

                    changeStatus(ConnectionStatus.Disconnected, e.getLocalizedMessage());
                }else {
                    _session = mFuture.getSession();

                    Log.d(TAG, "连接服务器成功...");

                    changeStatus(ConnectionStatus.Connected, "");

                    //
                    sendLoginMsg();

                    break;
                }


            } catch (Exception e) {
                Log.e(TAG, "连接服务器错误！" + e.toString());


                releaseConnector();
                changeStatus(ConnectionStatus.Disconnected, "");

                e.printStackTrace();
            }

            count ++;
        }while (count < retryCount);


    }

    /**
     * 更改状态并通知ui
     *
     * @param status
     */
    private void changeStatus(ConnectionStatus status, String message) {
        _status = status;
        EventBus.getDefault().post(new ConnectionStatusChangedEvent(_status, message));
    }


    /**
     * 释放connector资源
     */
    private void releaseConnector() {
        if (_connector != null) {
            _connector.getFilterChain().clear();
            _connector.dispose();
            _connector = null;
        }
    }


    // 是否正在关闭
    public void shutdown() {
        Log.d(TAG, "关闭连接...");

        if (_connectThread != null && _connectThread.isAlive()){
            _connectThread.interrupt();
            _connectThread = null;
        }

        releaseConnector();

        if (_session != null && _session.isConnected()) {
            _session.closeNow();
            _session = null;
        }
    }


    /**
     * 向pc发送切换静音消息
     */
    public void sendToggleMuteMsg() {
        ToggleMuteMessage msg = new ToggleMuteMessage();
        try {
            sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendLoginMsg(){
        DeviceLoginMessage msg = new DeviceLoginMessage();
        msg.ConnectionCode = _config.ConnectionCode;
        msg.Version = BuildConfig.VERSION_NAME;
        msg.DeviceName = Build.MODEL + "(" + Build.MANUFACTURER + " " + Build.PRODUCT + ")";

        sendMessage(msg);
    }

    /**
     * 发送调整音量消息
     *
     * @param volume
     */
    public void sendUpdateVolumeMsg(int volume) {
        UpdateVolumeMessage msg = new UpdateVolumeMessage();
        msg.MasterVolume = volume;

        try {
            sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送按钮按下消息
     *
     * @param buttonIndex
     */
    public void sendButtonClickMsg(int buttonIndex) {
        ButtonClickedMessage msg = new ButtonClickedMessage();
        msg.ButtonIndex = buttonIndex;

        try {
            sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送普通文本消息
     *
     * @param type
     * @param text
     */
    public void sendTextMsg(int type, String text) {
        TextDataMessage msg = new TextDataMessage();
        msg.DataType = type;
        msg.Data = text;

        try {
            sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送通用的命令消息
     * @param command
     * @param data
     */
    public void sendCommandMsg(String command, String data){
        CommandMessage msg = new CommandMessage();
        msg.Command = command;
        msg.Data = data;

        try {
            sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 请求PC重发当前状态，用于切换屏幕后刷新界面
     */
    public void requestReSendState(){
        sendCommandMsg(CommandMessage.RESEND_STATE, "");
    }

    /**
     * 发送图片消息
     *
     * @param fileName
     * @param content
     */
    public void sendPhotoMsg(String fileName, byte[] content) {
        PhotoMessage msg = new PhotoMessage();
        msg.FileName = fileName;
        msg.Data = Base64.encodeToString(content, Base64.DEFAULT);

        sendMessage(msg);
    }

    /**
     * 连接断开
     * @param event
     */

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(SessionClosedEvent event){
        this.changeStatus(ConnectionStatus.Disconnected, "已断开");
    }


    /**
     * 处理收到的pc消息
     *
     * @param event
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(ServerMessageEvent event) {
        MessageBase originMessage = event.serverMessage;

        if (originMessage instanceof LoginStateMessage){



            LoginStateMessage msg = (LoginStateMessage)originMessage;

            Log.d(TAG, "登录状态：" + msg.IsLoggedIn.toString());

            if (msg.IsLoggedIn){
                //updateConnectionStatus(ConnectionStatus.LoggedIn, msg.ErrorMessage);
                this.changeStatus(ConnectionStatus.LoggedIn, msg.ErrorMessage);
            }else{
                this.changeStatus(ConnectionStatus.LoginFailed, msg.ErrorMessage);
            }
        }
    }

}
