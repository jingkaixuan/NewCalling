/*
 * Copyright (c) 2022 China Mobile Communications Group Co.,Ltd. All rights reserved.
 *
 * Licensed under the XXXX License, Version X.X (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://xxxxxxx/licenses/LICENSE-X.X
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cmcc.newcalllib.manage.ext.stt

import android.app.Activity
import android.content.Intent
import android.telecom.Call
import android.text.TextUtils
import bolts.Task
import com.cmcc.newcalllib.adapter.network.NetworkAdapter
import com.cmcc.newcalllib.adapter.translate.TranslateWindowHolder
import com.cmcc.newcalllib.adapter.translate.bean.TranslateBean
import com.cmcc.newcalllib.manage.entity.NewCallException
import com.cmcc.newcalllib.manage.entity.Results
import com.cmcc.newcalllib.manage.ext.ExtensionManager
import com.cmcc.newcalllib.manage.support.Callback
import com.cmcc.newcalllib.tool.LogUtil
import com.cmcc.newcalllib.tool.toStr
import com.google.gson.Gson
import org.json.JSONException
import java.nio.ByteBuffer

/**
 * STT manager class
 * @author jihongfei
 * @createTime 2023/1/12 14:14
 */
class STTManager(private val extensionManager: ExtensionManager) :
        STTController {
    //
    var mActivity: Activity? = null

    // DC label for SDK STT use
    var mTranslateDcLabel: String? = null
    var mTranslateTextSize: Int = 0
    var mTranslateJsonData = ""

    // 实时翻译、语音转写
    private var mTranslateWindowHolder: TranslateWindowHolder? = null


    private fun getActivityOrThrow(): Activity {
        if (mActivity != null) {
            return mActivity!!
        }
        if (extensionManager.cxt is Activity) {
            return extensionManager.cxt
        }
        throw NewCallException("STT no activity")
    }

    /**
     * 注册label数据的拦截器
     */
    private fun registerDataInterceptor(dcLabel: String) {
        extensionManager.networkAdapter.registerDataInterceptor(object : NetworkAdapter.DataInterceptor {
            override fun provideDataChannelLabel(): String {
                return dcLabel
            }

            override fun onDataArrive(data: ByteBuffer): Boolean {
                // 收到智能翻译数据
                mTranslateJsonData = data.toStr()
                LogUtil.d("STTManager", "onDataArrive. mTranslateJsonData: $mTranslateJsonData")
                if (mTranslateWindowHolder == null) {
                    return true
                }
                updateTranslateUI(mTranslateJsonData, mTranslateTextSize);
                return true
            }
        })
    }

    /**
     * Dialer API：启动智能翻译
     * @param dcLabel dclable 名称
     * @param callback
     */
    override fun enableSTT(
            textSize: Int,
            dcLabel: String,
            callback: Callback<Results<Boolean>>?
    ) {
        LogUtil.d("STTManager", "enableSTT.init")
        // 注册数据拦截器
        if (mTranslateDcLabel == null) {
            mTranslateDcLabel = dcLabel
            mTranslateTextSize = textSize
            registerDataInterceptor(dcLabel)
            LogUtil.d("STTManager", "set dcLabel first: $dcLabel")
        } else if (mTranslateDcLabel != dcLabel) {
            throw NewCallException("dcLabel different with previous in STTManager")
        }
        // init
        initTranslateControlWindow()
        // 更新字体大小
        updateTranslateUI(mTranslateJsonData, mTranslateTextSize)
    }

    override fun disableSTT(callback: Callback<Results<Boolean>>?) {
        LogUtil.d("STTManager", "disableSTT")
        exitTranslateControlWindow()
        mTranslateWindowHolder = null
    }

    /**
     * SDK API：电话状态变化
     */
    override fun onCallStateChanged(state: Int) {
        LogUtil.d("ScreenShareManager, onCallStateChanged state=$state")
        // 屏幕共享如果不是开启状态，不进行回调处理
        if (mTranslateWindowHolder == null) {
            return;
        }
        if (state == Call.STATE_DISCONNECTED) {
            disableSTT(null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        LogUtil.d("STTManager, onActivityResult, req=$requestCode, res=$resultCode")
        // 屏幕共享如果不初始化，不进行回调处理
        if (mTranslateWindowHolder == null) {
            return;
        }
        onTranslateWindowResult(requestCode, resultCode, data)
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~实时翻译 Begin~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    /**
     * 展示屏幕涂鸦悬浮窗
     */
    private fun initTranslateControlWindow() {
        LogUtil.d("STTManager: ", "initTranslateControlWindow")
        // 创建涂鸦Window
        if (mTranslateWindowHolder == null) {
            LogUtil.d("ScreenShareManager: ", "initSketchControlWindow：new mSketchWindowHolder")
            mTranslateWindowHolder = TranslateWindowHolder(getActivityOrThrow())
        }
    }

    /**
     * 退出屏幕悬浮窗
     */
    private fun exitTranslateControlWindow() {
        LogUtil.d("STTManager: ", "exitTranslateControlWindow")
        // 退出：退出屏幕悬浮
        mTranslateWindowHolder?.hideTranslateWindow()
    }

    /**
     * 弹窗权限申请
     */
    private fun onTranslateWindowResult(requestCode: Int, resultCode: Int, data: Intent?) {
        LogUtil.d("ScreenShareManager: ", "onSketchWindowResult")
        // 有权限申请弹窗
        mTranslateWindowHolder?.onActivityResult(requestCode, resultCode, data)
    }


    /**
     * 接收到显示数据
     * @param jsonData {"contentId":"contentId","contentType":2,"content":"你好!","contentEng":"Hello","time":1676448103104}
     * // contentId: 消息id
     * // contentType: 1 语音转写;  2 实时翻译
     * // time : 当前时间戳
     * // content: 中文内容
     * // contentEng: 要翻译的语言内容
     * @param txtSizeDp
     */
    private fun updateTranslateUI(jsonData: String, txtSizeDp: Int) {
        LogUtil.d("STTManager: ", "updateTranslateUI. data: $jsonData textSize: $txtSizeDp")
        if (!TextUtils.isEmpty(jsonData)) {
            // 1、解析json
            // 2、主线程更新UI
            var translateBean: TranslateBean
            try {
                translateBean = Gson().fromJson(jsonData, TranslateBean::class.java)
            } catch (e: JSONException) {
                e.printStackTrace()
                return
            }
            LogUtil.d("STTManager: ", "translateBean. $translateBean")
            // UI_Thread
            Task.call({
                // 1、检测悬浮窗权限 & 2、展示悬浮窗；
                mTranslateWindowHolder?.showTranslateWindow(translateBean)
                if (txtSizeDp > 0) {
                    mTranslateWindowHolder?.updateTranslateTextSize(txtSizeDp)
                }
                true
            }, Task.UI_THREAD_EXECUTOR)
        }
    }
}