import kotlinx.cinterop.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.DIRECTORY_SEPARATOR
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.posix.wcscpy
import platform.windows.*

lateinit var configPath: Path

val defaultConfigResolutions = listOf(
    5120 to 1440,
    3440 to 1440,
    2560 to 1440,
    1920 to 1080,
)
lateinit var configResolutions: List<Pair<Int, Int>>
lateinit var activeResolutions: List<Pair<Int, Int>>

data class Variable(val name: String, val value: String)

fun main(): Unit = runBlocking {
    memScoped {
        val localAppData = flow {
            val envBlock = GetEnvironmentStringsW()
            try {
                var envVar = envBlock
                while (envVar?.pointed != null) {
                    emit(envVar.toKStringFromUtf16().split("=").let { variable -> Variable(variable[0], variable[1]) })
                    envVar += lstrlen!!(envVar) + 1
                }
            } finally {
                FreeEnvironmentStringsW(envBlock)
            }
        }.first { it.name == "LOCALAPPDATA" }.value

        val configDirectory = "$localAppData${DIRECTORY_SEPARATOR}KRes".toPath()
        configPath = "$configDirectory${DIRECTORY_SEPARATOR}config.txt".toPath()

        configResolutions = try {
            FileSystem.SYSTEM.source(configPath).buffer().use { source ->
                buildList {
                    do {
                        val line = source.readUtf8Line()
                        line?.let {
                            val split = it.split(" ")
                            add(split[0].toInt() to split[1].toInt())
                        }
                    } while (line != null)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty {
            FileSystem.SYSTEM.createDirectories(configDirectory, true)
            FileSystem.SYSTEM.sink(configPath).buffer().use { sink ->
                defaultConfigResolutions.forEach { sink.writeUtf8("${it.first} ${it.second}\n") }
            }
            defaultConfigResolutions
        }

        val hInstance = GetModuleHandleW(null)
        val s = "KRes"

        val wndClass = alloc<WNDCLASSW> {
            lpfnWndProc = staticCFunction(::windowProc)
            this.hInstance = hInstance
            hCursor = LoadCursorW(null, IDC_ARROW)
            lpszClassName = s.wcstr.ptr
        }

        RegisterClassW(wndClass.ptr)

        val hWnd = CreateWindowExW(
            dwExStyle = 0u,
            lpClassName = s,
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

        val hIcon = LoadIconW(null, IDI_APPLICATION)

        val nid = alloc<NOTIFYICONDATAW> {
            cbSize = sizeOf<NOTIFYICONDATAW>().convert()
            this.hWnd = hWnd
            uID = 1u
            uFlags = (NIF_ICON or NIF_MESSAGE or NIF_TIP).convert()
            this.hIcon = hIcon
            uCallbackMessage = (WM_USER + 1).convert()
            szTip.toKStringFromUtf16()
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
                            val width = dm.dmPelsWidth.toInt()
                            val height = dm.dmPelsHeight.toInt()
                            add(width to height)
                        }
                    }

                    activeResolutions = configResolutions.filter(availableResolutions::contains)

                    activeResolutions.forEachIndexed { index, resolution ->
                        val id = index + 1000
                        AppendMenuW(
                            hMenu,
                            (MF_STRING or MF_ENABLED).convert(),
                            id.convert(),
                            "${resolution.first} x ${resolution.second}"
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
            val wmId = wParam.loword.toInt()
            when (wmId) {
                1 -> ShellExecuteW(null, "edit", configPath.toString(), null, null, SW_SHOW)
                2 -> PostQuitMessage(0)
                else -> {
                    val index = wmId - 1000
                    activeResolutions.getOrNull(index)?.let {
                        val dm = alloc<DEVMODE> {
                            dmSize = sizeOf<DEVMODE>().convert()
                            dmFields = (DM_PELSWIDTH or DM_PELSHEIGHT).convert()
                            dmPelsWidth = it.first.convert()
                            dmPelsHeight = it.second.convert()
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

private val ULong.hiword
    get() = this.shr(16) and 0xffffu
