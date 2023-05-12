import kotlinx.cinterop.*
import platform.posix.wcscpy
import platform.windows.*

lateinit var targetResolutions: List<Pair<Int, Int>>

fun main(): Unit = memScoped {
    val hInstance = GetModuleHandleW(null)
    val s = "MyWindowClass"

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
        lpWindowName = "Window",
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
    while (GetMessage!!(msg, hWnd, 0u, 0u) == TRUE) {
        TranslateMessage(msg)
        DispatchMessage!!(msg)
    }

    Shell_NotifyIconW(NIM_DELETE, nid.ptr)
    DestroyWindow(hWnd)
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

                    val hDC = GetDC(null)

                    val availableResolutions = buildSet {
                        var i = 0u
                        while (EnumDisplaySettings!!(null, i++, dm.ptr) == TRUE) {
                            val width = dm.dmPelsWidth.toInt()
                            val height = dm.dmPelsHeight.toInt()
                            add(width to height)
                        }
                    }

                    targetResolutions = listOf(
                        GetDeviceCaps(hDC, DESKTOPHORZRES) to GetDeviceCaps(hDC, DESKTOPVERTRES),
                        3120 to 1440,
                        2540 to 1440,
                    )
                    targetResolutions.forEachIndexed { index, resolution ->
                        val id = index + 1000
                        val flag = if (availableResolutions.contains(resolution)) MF_ENABLED else MF_DISABLED
                        AppendMenuW(
                            hMenu,
                            (MF_STRING or flag).convert(),
                            id.convert(),
                            "${resolution.first} x ${resolution.second}"
                        )
                    }

                    AppendMenuW(hMenu, MF_SEPARATOR.convert(), 0, null)
                    AppendMenuW(hMenu, (MF_STRING or MF_ENABLED).convert(), 1, "Exit")

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

                    // Destroy the menu after use
                    DestroyMenu(hMenu)
                }
            }
        }

        WM_COMMAND.toUInt() -> {
            val wmId = wParam.loword.toInt()
            when (wmId) {
                1 -> PostQuitMessage(0)
                else -> {
                    val index = wmId - 1000
                    targetResolutions.getOrNull(index)?.let { println(it) }
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
