/* Android loads the Asuka host and Rust core together through JNI. */
VERSION {
    {
        global: JNI_*; Java_*;
        local: *;
    };
}
