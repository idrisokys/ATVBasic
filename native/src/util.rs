//! JNI yardımcıları.

use jni::objects::{JIntArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::jobjectArray;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Panik JVM'e sızmasın: yakalanır ve `default` döner.
pub fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

pub fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.as_raw().is_null() {
        return None;
    }
    env.get_string(s).ok().map(String::from)
}

pub fn jstr_array(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    if arr.as_raw().is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(arr).unwrap_or(0);
    let mut v = Vec::with_capacity(n.max(0) as usize);
    for i in 0..n {
        match env.get_object_array_element(arr, i) {
            Ok(o) => {
                let js = JString::from(o);
                v.push(jstr(env, &js).unwrap_or_default());
                let _ = env.delete_local_ref(js);
            }
            Err(_) => v.push(String::new()),
        }
    }
    v
}

pub fn to_jstr_array(env: &mut JNIEnv, items: &[String]) -> jobjectArray {
    let cls = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(items.len() as i32, &cls, JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, s) in items.iter().enumerate() {
        if let Ok(js) = env.new_string(s) {
            let _ = env.set_object_array_element(&arr, i as i32, &js);
            let _ = env.delete_local_ref(js);
        }
    }
    arr.into_raw()
}

/// `int[]` içeriği; null ya da okunamazsa boş.
pub fn jint_array(env: &mut JNIEnv, arr: &JIntArray) -> Vec<i32> {
    if arr.as_raw().is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(arr).unwrap_or(0).max(0) as usize;
    let mut v = vec![0i32; n];
    if env.get_int_array_region(arr, 0, &mut v).is_err() {
        return Vec::new();
    }
    v
}

/// `long[]` içeriği; null ya da okunamazsa boş.
pub fn jlong_array(env: &mut JNIEnv, arr: &JLongArray) -> Vec<i64> {
    if arr.as_raw().is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(arr).unwrap_or(0).max(0) as usize;
    let mut v = vec![0i64; n];
    if env.get_long_array_region(arr, 0, &mut v).is_err() {
        return Vec::new();
    }
    v
}
