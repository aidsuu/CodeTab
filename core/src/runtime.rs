use std::sync::OnceLock;
use tokio::runtime::Runtime;

/// Multi-threaded so a blocking host-key prompt can use `block_in_place`.
pub(crate) fn rt() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("jangkau-io")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}
