//! Loads Valve's redist Steamworks library by hand, so the crate needs no external dependency.
//!
//! Horizon lets a mod's crate use only the crates already in Horizon's `Cargo.lock`, and a dynamic
//! loader crate is not among them. The two things the Flat API needs — open a library, look up an
//! exported function — are a handful of system calls, declared here directly.

use std::path::Path;

/// An open library. `Steam` owns one and is `Send`, so the handle crosses a thread boundary.
pub struct Library {
    handle: usize,
}

unsafe impl Send for Library {}

impl Library {
    pub fn open(path: &Path) -> Result<Self, String> {
        platform::open(path).map(|handle| Self { handle })
    }

    /// The address of an exported function, or `None` when the library does not have it. Every
    /// Steam entry point is an ordinary function, so the address *is* the callable pointer.
    ///
    /// # Safety
    /// The caller must only read the address as the function type named by `T`.
    pub unsafe fn address(&self, name: &str) -> Option<*const ()> {
        platform::address(self.handle, name)
    }
}

impl Drop for Library {
    fn drop(&mut self) {
        // `Steam` shuts the API down before its fields drop, so nothing from the library is
        // still in use here.
        unsafe { platform::close(self.handle) };
    }
}

#[cfg(windows)]
mod platform {
    use std::ffi::CString;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;

    // No `#[link]`: std already links kernel32, and asking for the import library again makes the
    // Windows GNU linker complain about its export def file.
    extern "system" {
        fn LoadLibraryExW(
            name: *const u16,
            file: *mut core::ffi::c_void,
            flags: u32,
        ) -> *mut core::ffi::c_void;
        fn GetProcAddress(module: *mut core::ffi::c_void, name: *const i8) -> *mut core::ffi::c_void;
        fn FreeLibrary(module: *mut core::ffi::c_void) -> i32;
    }

    /// Search the directory of the named DLL, so `steam_api64.dll` finds the `steamclient64.dll`
    /// shipped beside it rather than one from elsewhere on the machine.
    const LOAD_WITH_ALTERED_SEARCH_PATH: u32 = 0x8;

    pub fn open(path: &Path) -> Result<usize, String> {
        let wide: Vec<u16> = path
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        // The altered search path only makes sense for a path: for a bare module name the loader
        // documents the flag as ignored, and passing it with a relative path is undefined.
        let flags = if path.parent().is_some_and(|parent| !parent.as_os_str().is_empty()) {
            LOAD_WITH_ALTERED_SEARCH_PATH
        } else {
            0
        };
        let handle = unsafe { LoadLibraryExW(wide.as_ptr(), core::ptr::null_mut(), flags) };
        if handle.is_null() {
            let code = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
            return Err(format!("LoadLibraryExW failed with Windows error {code}"));
        }
        Ok(handle as usize)
    }

    /// # Safety
    /// The caller reads the returned address as a function pointer of their choosing.
    pub unsafe fn address(handle: usize, name: &str) -> Option<*const ()> {
        let cname = CString::new(name).ok()?;
        let pointer = GetProcAddress(handle as *mut _, cname.as_ptr());
        if pointer.is_null() {
            None
        } else {
            Some(pointer as *const ())
        }
    }

    /// # Safety
    /// `handle` must be a module this process loaded and nothing from it may still be in use.
    pub unsafe fn close(handle: usize) {
        FreeLibrary(handle as *mut _);
    }
}

#[cfg(not(windows))]
mod platform {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    use std::path::Path;

    #[link(name = "dl")]
    extern "C" {
        fn dlopen(filename: *const i8, flags: i32) -> *mut core::ffi::c_void;
        fn dlsym(handle: *mut core::ffi::c_void, symbol: *const i8) -> *mut core::ffi::c_void;
        fn dlclose(handle: *mut core::ffi::c_void) -> i32;
    }

    /// `RTLD_NOW` on both Linux and macOS.
    const RTLD_NOW: i32 = 2;

    pub fn open(path: &Path) -> Result<usize, String> {
        let name = CString::new(path.as_os_str().as_bytes())
            .map_err(|_| "the library path holds a NUL byte".to_string())?;
        let handle = unsafe { dlopen(name.as_ptr(), RTLD_NOW) };
        if handle.is_null() {
            return Err("dlopen found no Steam runtime at this path".to_string());
        }
        Ok(handle as usize)
    }

    /// # Safety
    /// The caller reads the returned address as a function pointer of their choosing.
    pub unsafe fn address(handle: usize, name: &str) -> Option<*const ()> {
        let cname = CString::new(name).ok()?;
        let pointer = dlsym(handle as *mut _, cname.as_ptr());
        if pointer.is_null() {
            None
        } else {
            Some(pointer as *const ())
        }
    }

    /// # Safety
    /// `handle` must be a library this process opened and nothing from it may still be in use.
    pub unsafe fn close(handle: usize) {
        dlclose(handle as *mut _);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_library_that_is_not_there_is_reported() {
        let outcome = Library::open(Path::new("e4steam-no-such-library"));
        let Err(error) = outcome else {
            panic!("a library that does not exist opened");
        };
        assert!(!error.is_empty());
    }

    #[test]
    fn the_steam_exports_come_from_the_library_named_by_the_environment() {
        let Some(path) = std::env::var_os("E4STEAM_STEAM_API").map(std::path::PathBuf::from) else {
            return;
        };
        let Ok(library) = Library::open(&path) else {
            return;
        };
        assert!(unsafe { library.address("SteamAPI_InitFlat") }.is_some());
        assert!(unsafe { library.address("SteamAPI_NotARealEntryPoint") }.is_none());
    }
}
