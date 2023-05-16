import kotlinx.cinterop.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okio.FileSystem.Companion.SYSTEM
import okio.Path
import okio.Path.Companion.DIRECTORY_SEPARATOR
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.posix.wcscpy
import platform.windows.*

lateinit var configPath: Path

data class Variable(val name: String, val value: String)
data class Resolution(val width: Int, val height: Int)

val defaultConfigResolutions = listOf(
    Resolution(5120, 1440),
    Resolution(3440, 1440),
    Resolution(2560, 1440),
    Resolution(1920, 1080),
)
lateinit var configResolutions: List<Resolution>
lateinit var activeResolutions: List<Resolution>

fun main() {
    runBlocking {
        memScoped {
            val localAppData = flow {
                val envBlock = GetEnvironmentStringsW()
                try {
                    var envVar = envBlock
                    while (envVar?.pointed != null) {
                        emit(
                            envVar.toKStringFromUtf16().split("=")
                                .let { variable -> Variable(variable[0], variable[1]) })
                        envVar += lstrlen!!(envVar) + 1
                    }
                } finally {
                    FreeEnvironmentStringsW(envBlock)
                }
            }.first { it.name == "LOCALAPPDATA" }.value

            val exePath = memScoped {
                allocArray<WCHARVar>(MAX_PATH)
                    .also { ptr -> GetModuleFileNameW(null, ptr, MAX_PATH.convert()) }
                    .toKStringFromUtf16()
            }

            val folderPath = exePath.toPath().parent

            val configDirectory = "$localAppData${DIRECTORY_SEPARATOR}KRes".toPath()
            configPath = "$configDirectory${DIRECTORY_SEPARATOR}config.txt".toPath()

            configResolutions = try {
                SYSTEM.source(configPath).buffer().use { source ->
                    buildList {
                        do {
                            val line = source.readUtf8Line()
                            line?.let {
                                val split = it.split(" ")
                                add(Resolution(split[0].toInt(), split[1].toInt()))
                            }
                        } while (line != null)
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }.ifEmpty {
                SYSTEM.createDirectories(configDirectory, true)
                SYSTEM.sink(configPath).buffer().use { sink ->
                    defaultConfigResolutions.forEach { sink.writeUtf8("${it.width} ${it.height}\n") }
                }
                defaultConfigResolutions
            }

            val hInstance = GetModuleHandleW(null)
            val appName = "KRes"

            val wndClass = alloc<WNDCLASSW> {
                lpfnWndProc = staticCFunction(::windowProc)
                this.hInstance = hInstance
                hCursor = LoadCursorW(null, IDC_ARROW)
                lpszClassName = appName.wcstr.ptr
            }

            RegisterClassW(wndClass.ptr)

            val hWnd = CreateWindowExW(
                dwExStyle = 0u,
                lpClassName = appName,
                lpWindowName = "KRes",
                dwStyle = WS_OVERLAPPEDWINDOW,
                X = CW_USEDEFAULT,
                Y = CW_USEDEFAULT,
                nWidth = CW_USEDEFAULT,
                nHeight = CW_USEDEFAULT,
                hWndParent = null,
                hMenu = null,
                hInstance = hInstance,
                lpParam = null,
            )

            val hIcon = runCatching {
                val icoPath = SYSTEM.canonicalize("$folderPath${DIRECTORY_SEPARATOR}KRes.ico".toPath())
                @Suppress("UNCHECKED_CAST")
                LoadImageW(
                    null,
                    icoPath.toString().wcstr.ptr,
                    1u,
                    0,
                    0,
                    (LR_DEFAULTSIZE or LR_LOADFROMFILE).convert()
                ) as HICON?
            }.getOrElse { LoadIconW(null, IDI_APPLICATION) }

            val nid = alloc<NOTIFYICONDATAW> {
                cbSize = sizeOf<NOTIFYICONDATAW>().convert()
                this.hWnd = hWnd
                uID = 1u
                uFlags = (NIF_ICON or NIF_MESSAGE or NIF_TIP).convert()
                this.hIcon = hIcon
                uCallbackMessage = (WM_USER + 1).convert()
                wcscpy(szTip, "".wcstr)
            }

            Shell_NotifyIconW(NIM_ADD, nid.ptr)

            val msg = alloc<MSG>().ptr
            while (GetMessageW(msg, hWnd, 0u, 0u) == TRUE) {
                TranslateMessage(msg)
                DispatchMessageW(msg)
            }

            Shell_NotifyIconW(NIM_DELETE, nid.ptr)
            DestroyWindow(hWnd)
        }
    }
}

fun windowProc(hWnd: HWND?, uMsg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT = memScoped {
    when (uMsg) {
        WM_USER.toUInt() + 1u -> {
            when (lParam) {
                WM_LBUTTONUP.toLong() -> {
                    val hMenu = checkNotNull(CreatePopupMenu()) { "Unable to create popup menu" }

                    val dm = alloc<DEVMODE> {
                        dmSize = sizeOf<DEVMODE>().convert()
                        dmDriverVersion = 0u
                    }

                    val availableResolutions = buildSet {
                        var i = 0u
                        while (EnumDisplaySettingsW(null, i++, dm.ptr) == TRUE) {
                            add(Resolution(dm.dmPelsWidth.toInt(), dm.dmPelsHeight.toInt()))
                        }
                    }

                    activeResolutions = configResolutions.filter(availableResolutions::contains)

                    activeResolutions.forEachIndexed { index, resolution ->
                        val id = index + 1000
                        AppendMenuW(
                            hMenu,
                            (MF_STRING or MF_ENABLED).convert(),
                            id.convert(),
                            "${resolution.width} x ${resolution.height}"
                        )
                    }

                    AppendMenuW(hMenu, MF_SEPARATOR.convert(), 0, null)
                    AppendMenuW(hMenu, (MF_STRING or MF_ENABLED).convert(), 1, "Config")
                    AppendMenuW(hMenu, (MF_STRING or MF_ENABLED).convert(), 2, "Exit")

                    val pos = alloc<POINT>()
                    GetCursorPos(pos.ptr)

                    SetForegroundWindow(hWnd)
                    TrackPopupMenu(
                        hMenu,
                        (TPM_LEFTALIGN or TPM_RIGHTBUTTON).convert(),
                        pos.x,
                        pos.y,
                        0,
                        hWnd,
                        null
                    )
                    PostMessageW(hWnd, WM_NULL, 0, 0)

                    DestroyMenu(hMenu)
                }
            }
        }

        WM_COMMAND.toUInt() -> {
            when (val wmId = wParam.loword.toInt()) {
                1 -> ShellExecuteW(null, "edit", configPath.toString(), null, null, SW_SHOW)
                2 -> PostQuitMessage(0)
                else -> {
                    val index = wmId - 1000
                    activeResolutions.getOrNull(index)?.let {
                        val dm = alloc<DEVMODE> {
                            dmSize = sizeOf<DEVMODE>().convert()
                            dmFields = (DM_PELSWIDTH or DM_PELSHEIGHT).convert()
                            dmPelsWidth = it.width.convert()
                            dmPelsHeight = it.height.convert()
                        }
                        ChangeDisplaySettingsW(dm.ptr, 1u)
                    }
                }
            }
        }

        else -> return DefWindowProcW(hWnd, uMsg, wParam, lParam)
    }

    return 0
}

private val ULong.loword
    get() = this and 0xffffu

@Suppress("unused")
private val ULong.hiword
    get() = this.shr(16) and 0xffffu
