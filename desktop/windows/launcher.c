/*
 * ParrotMetric.exe: runs the app's jars on the Java runtime installed next to
 * it, with the core's DLL in app\. It does what the Debian package's launcher
 * script does (deb/parrotmetric), as a Windows program, so there's no console
 * window and the Start menu and taskbar show the app's icon (launcher.rc).
 *
 *   ParrotMetric.exe
 *   runtime\bin\javaw.exe      Eclipse Temurin's Java runtime
 *   app\parrotmetric.dll       the core
 *   app\lib\*.jar              the app
 *
 * A file named on the command line (opening a design from Explorer) is passed on.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>

static void fail(const wchar_t *what) {
    MessageBoxW(NULL, what, L"ParrotMetric", MB_OK | MB_ICONERROR);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
    (void)instance; (void)previous; (void)show;
    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) { fail(L"Could not find where ParrotMetric is installed."); return 1; }
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash != NULL) *slash = 0;

    static wchar_t command[32768];
    int length = _snwprintf(command, 32768,
        L"\"%ls\\runtime\\bin\\javaw.exe\" "
        L"\"-Djava.library.path=%ls\\app\" --enable-native-access=ALL-UNNAMED "
        L"-cp \"%ls\\app\\lib\\*\" com.rm.parrotmetric.desktop.MainKt %ls",
        dir, dir, dir, args ? args : L"");
    if (length < 0) { fail(L"The command line is too long."); return 1; }

    STARTUPINFOW startup = {0};
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process = {0};
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, 0, NULL, dir, &startup, &process)) {
        fail(L"Could not start Java. The runtime folder beside ParrotMetric.exe may be missing: try installing again.");
        return 1;
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}
