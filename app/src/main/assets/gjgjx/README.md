# 搞机工具箱 Web - 网页版 Android 玩机工具

## <p align="center"><a href="https://www.bilibili.com/video/BV1TB456ZEoo">使用教程</a>&emsp;&emsp;<a href="https://czjam.github.io/gjgjxweb/">在线体验</a>   </p>
《搞机工具箱》是一款ADB图形化工具。

本项目移植于个人开发的 [《搞机工具箱》PC版](https://www.bilibili.com/video/BV1QvHhzLELb) 。借助 [Tango](https://github.com/yume-chan/ya-webadb) 实现了 WebADB，并通过 [xterm.js](https://github.com/xtermjs/xterm.js) 打通 Android 原生 Shell 交互式终端，可在支持Chrome内核的浏览器体验PC版工具功能，实现免下载安装的跨平台使用体验。

 [作者 @晨钟酱Official](https://space.bilibili.com/251013709)
 
 [官网: jamcz.com](https://jamcz.com)
 

## 功能

| 页面 | 主要功能 |
| --- | --- |
| 主页 | 设备与系统信息、CPU 频率、主板 ID 隐藏、重启、关机、重启至 Bootloader / Fastbootd / Recovery / EDL |
| 软件管理 | 包名与前台活动读取、启用/禁用、运行、强行停止、卸载与保留数据卸载、清除数据、软件信息、恢复系统软件、卸载锁定、操作记录、Google 套件批量管理 |
| 安装与文件传输 | APK 安装、拆分 APK 安装、推送文件、提取安装包 |
| 显示控制 | 分辨率、显示密度（DPI / dp）、按键模拟、截图 |
| 系统界面 | 状态栏图标隐藏、时钟与快捷设置、沉浸模式、Wi-Fi 网络检测相关设置 |
| 电池管理 | 电池信息、电池参数模拟与恢复、定时统计、可选功率记录、CSV 导出 |
| 无极调节 | 字体大小倍数、窗口/过渡/程序动画倍率、自动锁屏时间 |
| 控机脚本 | 脚本编辑与导入导出、命令插入、文件关联、部分 BAT 语法解释执行 |
| ADB 终端 | Android Shell 交互会话、ANSI 显示、命令历史、补全、复制、清屏与中断 |
| 软件设置 | 默认启动页、主题配色、界面缩放、CPU 更新间隔、文件接收路径、安装与截图选项、设置导入导出 |

当前界面提供 **USB 有线连接**。功能是否生效取决于设备的 Android 版本、ROM 和 ADB Shell 权限；免 ROOT 不代表能够绕过系统权限限制。进入 Fastboot 的按钮仅发送重启命令，本工具不提供 Fastboot 分区刷写功能。

## 快速开始

### 使用条件

- 使用支持 WebUSB 的 Chrome 或 Edge（不分操作系统，手机安装Chrome也能用）。
- 手机开启开发者选项和 USB 调试，使用 USB 数据线连接电脑。
- 通过本机 `localhost` / `127.0.0.1` 或 HTTPS 网站访问页面。
- Windows 需要能够正常识别手机的 ADB USB 接口，且手机端同意授权请求。


### 本地运行

仓库提供 **HTML、CSS、JavaScript 分离版** 与 **单HTML整合版**。页面和第三方依赖均保存在本地，可直接本地部署或执行，无需构建或安装 npm 依赖。

#### 方法1: 整合版
下载项目docs中的index.html即可。已内嵌完整依赖，使用Chrome内核浏览器直接本地运行。

#### 方法2: 分离版
1. 下载并解压完整项目，保留目录结构。
2. 安装 [Node.js](https://nodejs.org/)，在包含 `server.cjs` 的目录打开终端。
3. 运行：

   ```powershell
   node server.cjs
   ```

4. 使用 Chrome 或 Edge 打开 [http://127.0.0.1:8124](http://127.0.0.1:8124)。
5. 点击“连接设备”，在浏览器中选择手机；首次连接时，在手机上确认 USB 调试授权。

按 `Ctrl+C` 停止服务。服务只监听本机地址 `127.0.0.1`。

若默认端口被占用，可在 PowerShell 中更换端口：

```powershell
$env:PORT = "8080"
node server.cjs
```

随后打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)。也可以使用其他能够正确提供 ES 模块的静态网页服务器。

**请通过网页服务器打开分离版。** 直接双击 `index.html` 会使用 `file://` 地址，外部 ES 模块可能因浏览器的跨源限制而加载失败，导致连接按钮不可用。参见 [MDN：JavaScript 模块](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules)。

## 部署到网站

将本目录中的完整文件上传到支持 HTTPS 的静态托管服务或 Web 服务器，以 `index.html` 为入口即可。第三方依赖已包含在 `js/vendor/` 中，运行时无需额外从 CDN 下载依赖，也不需要部署 ADB 后端。

- 保留 `index.html`、`css/`、`js/`、`assets/` 的相对路径。
- 对外提供服务时使用 HTTPS。本机回环地址属于浏览器认可的可信来源，因此本地开发可以使用 HTTP。参见 [MDN：安全上下文](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Secure_Contexts)。
- JavaScript 应返回有效的 JavaScript MIME 类型，例如 `text/javascript`；CSS 应返回 `text/css`。不要将它们统一返回为 `application/octet-stream`。
- 如果自行改为 CDN 引用，脚本、样式和模块依赖也应使用 HTTPS，避免被浏览器作为混合内容屏蔽。
- `server.cjs` 是可选的本地启动工具；部署到静态托管平台后，访问者无需安装 Node.js。

WebUSB 的授权和使用条件可参考 [Chrome WebUSB 文档](https://developer.chrome.com/docs/capabilities/usb)。

## 目录结构

```text
.
├── index.html                 # 页面结构与模块入口
├── README.md                  # 项目说明
├── LICENSE                    # 项目 GPLv3 许可证全文
├── server.cjs                 # 本地静态服务器（Node.js 标准库）
├── LICENSE-Tango.txt          # Tango 相关组件许可
├── LICENSE-xterm.txt          # xterm.js 相关组件许可
├── assets/
│   └── favicon.svg            # 页面图标
├── docs/
│   └── index.html            # 完整本地运行文件
├── css/
│   ├── styles.css             # 工具箱样式
│   └── xterm.css              # 终端样式
└── js/
    ├── app.js                 # 页面初始化、交互、连接状态与设置
    ├── core.js                # 命令执行与各功能逻辑
    ├── transport.js           # Tango / WebUSB 连接与传输
    ├── terminal.js            # 交互式终端会话
    ├── batch.js               # BAT 语法子集解释器
    └── vendor/                # 本地第三方依赖
```

## 终端与脚本

### ADB 终端

终端连接的是手机上的 Android Shell，可直接输入 Shell 命令：

```sh
getprop ro.product.model
wm size
settings get system font_scale
```

同一连接中的终端会话保留当前目录和环境变量；切换页面不会重新创建会话。历史记录和补全由设备上的 Shell 提供，`Ctrl+C` 可中断正在运行的命令。断开设备或重新启动终端会结束当前会话。

### BAT 脚本

控机脚本通过 JavaScript 解释部分 BAT 语法，将其中的 ADB 操作转换为浏览器端调用，**不会启动 Windows 的 `cmd.exe` 或执行电脑上的 EXE**。

例如，可以导入并执行以下脚本：

```bat
@echo off
echo 读取设备型号
adb shell getprop ro.product.model
timeout /t 1
adb shell wm size
```

支持的语法包括 `echo`、`rem`、`set`、`set /a`、`set /p`、变量替换、标签与 `goto`、部分 `if` 条件、`if errorlevel`、多行括号形式的 `for /l`、`timeout`、`choice /t`、`pause` 和 `exit /b`。具体支持范围以解释器实现为准。

涉及 `adb push`、`adb install` 等本地文件操作时，需要先在页面中关联对应文件。浏览器无法仅凭脚本中的 Windows 路径读取电脑文件。

这套解释器兼容的是 BAT 语法子集。依赖外部程序、PowerShell、`for /f` 或复杂 CMD 语法的脚本需要调整；遇到不支持的命令时会报告错误。

## 数据保存与文件下载

- **设置、脚本文本、软件操作记录**：保存在当前网站的浏览器本地存储中。清理网站数据会删除这些内容；设置和脚本可通过页面提供的功能导出备份。
- **ADB 身份密钥**：由 Tango 保存在浏览器 IndexedDB 中。可在断开设备后，通过软件设置清除。
- **网站地址变更**：不同协议、域名或端口使用独立的网站存储，迁移部署地址后可能需要重新授权和导入设置。
- **截图与电池统计**：需要及时下载保存，当前页面内的临时数据不会跨刷新保留。
- **电池 CSV**：文件名使用连接设备读取到的机型名称和日期时间，例如 `一加 15T_2026-11-03_14-30-00.csv`；无法读取营销名称时回退到系统型号。
- **下载目录**：可在支持的浏览器中授权保存目录；未授权时使用普通浏览器下载。截图“下载后删除手机原图”仅在确认写入所选目录后执行，普通浏览器下载会保留手机原图。

设备命令通过浏览器与手机之间的 USB 连接执行，不经过本项目的远程 ADB 服务。文件与目录访问需要用户通过浏览器选择或授权。

## 常见问题

| 问题 | 排查方法 |
| --- | --- |
| 连接按钮是灰色的 | 使用 Chrome / Edge，通过本地服务器或 HTTPS 打开完整目录；检查开发者工具中的模块加载错误、404 和 MIME 类型错误。 |
| 提示当前 ADB 接口被占用 | 在任务管理器中结束占用接口的 `adb.exe`，关闭其他 ADB 工具及已连接设备的 WebADB 页面，再重新连接。 |
| 浏览器找不到设备 | 检查 USB 调试、数据线与 USB 接口，以及 Windows 下的 ADB 驱动；确认数据线支持数据传输。 |
| 手机没有授权弹框 | 解锁手机并检查 USB 调试；已有有效授权时可能不会再次弹框。仍无法连接时，可在手机开发者选项撤销 USB 调试授权后重试。 |
| 资源被屏蔽为 `mixed-content` | HTTPS 页面引用了 HTTP 资源，需将相关资源及其依赖改为 HTTPS。 |
| 提示 `application/octet-stream` 或 MIME 错误 | 检查静态资源服务器的响应头，按扩展名设置正确的 JavaScript / CSS 类型。 |
| 命令返回权限不足、设置不生效 | Android 版本、厂商 ROM 和系统策略可能限制 ADB Shell 权限；部分设备还需开启厂商提供的 USB 调试安全选项。 |
| CPU 频率、功率或主板 ID 无法读取 | 部分设备不开放对应系统节点。功率记录依赖设备提供的电流、电压等数据，不等同于外接充电功率计测量值。 |
| 关闭或刷新页面后连接断开 | USB 会话依附于当前页面，需要重新连接；请提前下载需要保留的数据。 |

## 开发与反馈

本分离版直接使用原生 JavaScript ES 模块。修改后启动 `server.cjs` 并刷新页面即可查看效果，无需执行依赖安装或打包命令。

- 修改页面布局：`index.html`。
- 修改样式：`css/styles.css`。
- 修改页面交互与设置：`js/app.js`。
- 修改设备命令和功能逻辑：`js/core.js`。
- 修改 USB 连接、终端或脚本解释：分别查看 `js/transport.js`、`js/terminal.js`、`js/batch.js`。
- 更新第三方组件时，检查模块引用与接口兼容性，并保留相应的版权及许可证文件。

欢迎通过仓库的 Issues 反馈问题或提交 Pull Request。反馈时请提供工具版本、浏览器版本、电脑系统、设备型号、Android / ROM 版本、复现步骤和相关错误信息。提交截图或命令输出前，请隐藏设备序列号、主板 ID 等个人设备信息。

涉及设备行为的修改，请说明实际测试设备及结果；未经实机验证的行为请明确注明。

## 第三方项目

感谢以下开源项目：

| 项目 | 使用组件与版本 | 许可 |
| --- | --- | --- |
| [Tango / ya-webadb](https://github.com/yume-chan/ya-webadb) | `@yume-chan/adb` 2.6.4、`adb-daemon-webusb` 2.3.2、`adb-credential-web` 2.1.0、`stream-extra` 2.6.1、`struct` 2.3.2、`event` 2.0.0、`async` 4.1.3、`no-data-view` 2.0.0 | [MIT](LICENSE-Tango.txt) |
| [xterm.js](https://github.com/xtermjs/xterm.js) | `@xterm/xterm` 5.5.0、`@xterm/addon-fit` 0.10.0 | [MIT](LICENSE-xterm.txt) |

### 项目许可

本项目的原创代码采用 **GNU General Public License v3.0（GPL-3.0-only）**，许可证全文见 [LICENSE](LICENSE)。

Copyright (C) 2026 晨钟酱Official。

第三方组件保留各自的版权与 MIT 许可，详见 [LICENSE-Tango.txt](LICENSE-Tango.txt) 和 [LICENSE-xterm.txt](LICENSE-xterm.txt)。分发时请保留这些声明。

本软件按许可证所述提供，不附带任何担保。

## 使用说明

本工具为全安卓通用软件。使用本工具则代表您有一定玩机知识储备。若盲目操作导致无法开机或丢失数据则后果自负，作者不承担任何责任。
