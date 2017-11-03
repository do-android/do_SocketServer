package doext.implement;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;
import core.DoServiceContainer;
import core.object.DoSingletonModule;

import org.json.JSONException;
import org.json.JSONObject;

import core.helper.DoIOHelper;
import core.helper.DoJsonHelper;
import core.interfaces.DoIScriptEngine;
import core.object.DoInvokeResult;
import doext.define.do_SocketServer_IMethod;

/**
 * 自定义扩展SM组件Model实现，继承DoSingletonModule抽象类，并实现do_SocketServer_IMethod接口方法；
 * #如何调用组件自定义事件？可以通过如下方法触发事件：
 * this.model.getEventCenter().fireEvent(_messageName, jsonResult);
 * 参数解释：@_messageName字符串事件名称，@jsonResult传递事件参数对象； 获取DoInvokeResult对象方式new
 * DoInvokeResult(this.getUniqueKey());
 */
public class do_SocketServer_Model extends DoSingletonModule implements do_SocketServer_IMethod {

	private List<Socket> socketList = new ArrayList<Socket>();
	private volatile ServerSocket server = null;
	private ExecutorService mExecutorService = null; // 线程池
	private boolean flag = false;// 线程标志位
	final int minPort = 0;
	final int maxPort = 65535;
	Context _context;

	public do_SocketServer_Model() throws Exception {
		super();
		IntentFilter mFilter = new IntentFilter();
		mFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		_context = DoServiceContainer.getPageViewFactory().getAppContext();
		_context.registerReceiver(myNetReceiver, mFilter);

	}

	private BroadcastReceiver myNetReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
				ConnectivityManager mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo netInfo = mConnectivityManager.getActiveNetworkInfo();
				if ((netInfo == null || !netInfo.isConnected()) && socketList.size() > 0) {
					try {
						fireErrorEvent("网络断开连接");
					} catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
	};

	/**
	 * 同步方法，JS脚本调用该组件对象方法时会被调用，可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V），获取参数值使用API提供DoJsonHelper类；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_invokeResult 用于返回方法结果对象
	 */
	@Override
	public boolean invokeSyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		if ("startListen".equals(_methodName)) {
			this.startListen(_dictParas, _scriptEngine, _invokeResult);
			return true;
		}
		if ("stopListen".equals(_methodName)) {
			this.stopListen(_dictParas, _scriptEngine, _invokeResult);
			return true;
		}
		return super.invokeSyncMethod(_methodName, _dictParas, _scriptEngine, _invokeResult);
	}

	/**
	 * 异步方法（通常都处理些耗时操作，避免UI线程阻塞），JS脚本调用该组件对象方法时会被调用， 可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V），获取参数值使用API提供DoJsonHelper类；
	 * @_scriptEngine 当前page JS上下文环境
	 * @_callbackFuncName 回调函数名 #如何执行异步方法回调？可以通过如下方法：
	 *                    _scriptEngine.callback(_callbackFuncName,
	 *                    _invokeResult);
	 *                    参数解释：@_callbackFuncName回调函数名，@_invokeResult传递回调函数参数对象；
	 *                    获取DoInvokeResult对象方式new
	 *                    DoInvokeResult(this.getUniqueKey());
	 */
	@Override
	public boolean invokeAsyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, String _callbackFuncName) throws Exception {
		if ("send".equals(_methodName)) {
			this.send(_dictParas, _scriptEngine, _callbackFuncName);
			return true;
		}
		return super.invokeAsyncMethod(_methodName, _dictParas, _scriptEngine, _callbackFuncName);
	}

	/**
	 * 发送数据；
	 * 
	 * @throws Exception
	 * 
	 * @_dictParas 参数（K,V），可以通过此对象提供相关方法来获取参数值（Key：为参数名称）；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_callbackFuncName 回调函数名
	 */
	@Override
	public void send(JSONObject _dictParas, DoIScriptEngine _scriptEngine, String _callbackFuncName) throws Exception {
		final String _content = DoJsonHelper.getString(_dictParas, "content", "");
		final String _type = DoJsonHelper.getString(_dictParas, "type", "");
		final String _clientIP = DoJsonHelper.getString(_dictParas, "clientIP", null);
		if (server != null) {
			if (TextUtils.isEmpty(_content)) {
				callBack(false, _scriptEngine, _callbackFuncName);
				return;
			}
			try {
				byte[] _sendByte;
				if (_type.equalsIgnoreCase("HEX")) {// 发送十六进制数
					_sendByte = hexStr2Byte(_content);
				} else if (_type.equalsIgnoreCase("File")) {// 发送文件
					String path = DoIOHelper.getLocalFileFullPath(_scriptEngine.getCurrentPage().getCurrentApp(), _content);
					_sendByte = DoIOHelper.readAllBytes(path);
				} else if (_type.equalsIgnoreCase("gbk")) {
					_sendByte = _content.getBytes("GBK");
				} else {
					_sendByte = _content.getBytes("utf-8");
				}
				boolean checkSend = sendmsg(_sendByte, _clientIP);
				callBack(checkSend, _scriptEngine, _callbackFuncName);
			} catch (Exception e) {
				callBack(false, _scriptEngine, _callbackFuncName);
				DoServiceContainer.getLogEngine().writeError("发送异常", e);
			}
		} else {
			DoServiceContainer.getLogEngine().writeInfo("尚未开启监听或已结束监听", "do_SocketServer");
		}
	}

	public boolean sendmsg(final byte[] msg, final String clientIp) throws Exception {
		boolean result = false;
		for (int i = 0; i < socketList.size(); i++) {
			String address = socketList.get(i).getInetAddress().getHostAddress();
			if (TextUtils.isEmpty(clientIp)) {
				result = print(msg, socketList.get(i));
			} else {
				if (address.equals(clientIp)) {
					result = print(msg, socketList.get(i));
				} else {
					result = false;
				}
			}
		}
		return result;
	}

	private boolean print(byte[] data, Socket socket) throws Exception {
		try {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			out.write(data);
			return true;
		} catch (Exception e) {
			socketList.remove(socket);
			return false;
		}
	}

	public void callBack(boolean result, DoIScriptEngine _scriptEngine, String _callbackFuncName) {
		DoInvokeResult _invokeResult = new DoInvokeResult(getUniqueKey());
		_invokeResult.setResultBoolean(result);
		_scriptEngine.callback(_callbackFuncName, _invokeResult);
	}

	/**
	 * 开启监听；
	 * 
	 * @_dictParas 参数（K,V），可以通过此对象提供相关方法来获取参数值（Key：为参数名称）；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_invokeResult 用于返回方法结果对象
	 */
	@Override
	public void startListen(JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		int _serverPort = DoJsonHelper.getInt(_dictParas, "serverPort", 9999);
		if (flag == true) {
			DoServiceContainer.getLogEngine().writeError("do_SocketServer startListen", new Exception("监听已开启 请勿重复监听"));
			_invokeResult.setResultBoolean(false);
		} else if (_serverPort < minPort || _serverPort > maxPort || isUsed(_serverPort)) {
			DoServiceContainer.getLogEngine().writeError("do_SocketServer startListen", new Exception("端口号不在正确范围内或已被占用"));
			_invokeResult.setResultBoolean(false);
		} else {
			registerReceiver();
			flag = true;
			ServerThread serverThread = new ServerThread(_serverPort);
			serverThread.start();
			_invokeResult.setResultBoolean(true);
		}
	}

	// 判断端口号是否被占用
	private boolean isUsed(int _serverPort) {
		try {
			ServerSocket sosket = new ServerSocket(_serverPort);
			sosket.close();
			sosket = null;
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	private void registerReceiver() {
		IntentFilter mFilter = new IntentFilter();
		mFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		_context.registerReceiver(myNetReceiver, mFilter);
	}

	/**
	 * 结束监听；
	 * 
	 * @_dictParas 参数（K,V），可以通过此对象提供相关方法来获取参数值（Key：为参数名称）；
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_invokeResult 用于返回方法结果对象
	 */
	@Override
	public void stopListen(JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		doDispose();
	}

	private void doDispose() throws IOException {
		flag = false;
		clearClient();
		if (server != null) {
			server.close();
			server = null;
			mExecutorService.shutdownNow();
			_context.unregisterReceiver(myNetReceiver);
		}
	}

	private void clearClient() throws IOException {
		for (Socket socket : socketList) {
			socket.shutdownInput();
			socket.shutdownOutput();
			socket.close();
			socket = null;
		}
		socketList.clear();
	}

	// Server端的主线程
	class ServerThread extends Thread {
		int _port = 9999;

		public ServerThread(int port) {
			_port = port;
		}

		public void run() {
			try {
				server = new ServerSocket(_port);
				mExecutorService = Executors.newCachedThreadPool(); // 创建一个线程池
				while (flag) {
					try {
						Socket client = server.accept();
						socketList.add(client);
						fireListenEvent(client);
						mExecutorService.execute(new Service(client));
					} catch (IOException e) {
						e.printStackTrace();
					} catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
	}

	class Service implements Runnable {
		private InputStream in;
		Socket _socket;

		public Service(Socket socket) {
			try {
				_socket = socket;
				in = socket.getInputStream();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void run() {
			try {
				while (flag) {
					int length = 0;
					byte[] buff = new byte[1024];
					while ((length = in.read(buff)) != -1) {
						String _result = bytesToHexString(buff, length);
						fireReceiveEvent(_socket.getInetAddress().getHostAddress() + ":" + _socket.getPort(), _result);
					}
				}
			} catch (IOException e) {
				try {
					fireErrorEvent(e.getMessage());
				} catch (JSONException e1) {
					e1.printStackTrace();
				}
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public void fireReceiveEvent(String client, String msg) throws JSONException {
		if (socketList.size() > 0) {
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("receiveData", msg);
			jsonObject.put("client", client);
			fireEvent("receive", jsonObject);
		}
	}

	public void fireListenEvent(Socket client) throws JSONException {
		String address = client.getInetAddress().getHostAddress();
		int port = client.getPort();
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("ip", address + ":" + port);
		fireEvent("listen", jsonObject);
	}

	public void fireErrorEvent(String error) throws JSONException {
		if (socketList.size() > 0) {
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("msg", error);
			fireEvent("error", jsonObject);
			socketList.clear();
		}
	}

	private void fireEvent(String event, JSONObject jsonObject) {
		DoInvokeResult _invokeResult = new DoInvokeResult(getUniqueKey());
		_invokeResult.setResultNode(jsonObject);
		if (getEventCenter() != null) {
			getEventCenter().fireEvent(event, _invokeResult);
		}
	}

	public static String bytesToHexString(byte[] src, int len) {
		StringBuilder stringBuilder = new StringBuilder("");
		if (src == null || src.length <= 0) {
			return "";
		}
		for (int i = 0; i < len; i++) {
			int v = src[i] & 0xFF;
			String hv = Integer.toHexString(v);
			if (hv.length() < 2) {
				stringBuilder.append(0);
			}
			stringBuilder.append(hv);
		}
		return stringBuilder.toString();
	}

	public static byte[] hexStr2Byte(String hex) {
		int len = (hex.length() / 2);
		byte[] result = new byte[len];
		char[] achar = hex.toCharArray();
		for (int i = 0; i < len; i++) {
			int pos = i * 2;
			result[i] = (byte) (toByte(achar[pos]) << 4 | toByte(achar[pos + 1]));
		}
		return result;
	}

	public static byte toByte(char c) {
		byte b = (byte) "0123456789ABCDEF".indexOf(c);
		return b;
	}
}