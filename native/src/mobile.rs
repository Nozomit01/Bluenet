use crate::{
    android_tap,
    network::{NetworkPassword, NetworkRequest},
    output::ReportDirectory,
    protocol::Identity,
    runtime::{self, Command, Options, Update},
    session::{ProvisionProgress, Session},
    DEFAULT_BOOTSTRAP_HOST, SERVER_MODULUS,
};
use anyhow::{bail, ensure, Context, Result};
use jni::{
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::{jboolean, jlong, jstring},
    JNIEnv, JavaVM,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    net::Ipv4Addr,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender},
        Arc, Mutex, OnceLock,
    },
    thread::{self, JoinHandle},
};
use zeroize::{Zeroize, Zeroizing};

struct Run {
    generation: u64,
    stop: Arc<AtomicBool>,
    commands: SyncSender<Command>,
    ids: AtomicU64,
    vm: JavaVM,
    callbacks: GlobalRef,
}
struct Controller {
    generation: u64,
    current: Option<Arc<Run>>,
    worker: Option<JoinHandle<()>>,
    issued: Option<Identity>,
    status: Value,
}
static CONTROL: OnceLock<Mutex<Controller>> = OnceLock::new();
fn controller() -> &'static Mutex<Controller> {
    CONTROL.get_or_init(|| {
        Mutex::new(Controller {
            generation: 0,
            current: None,
            worker: None,
            issued: None,
            status: json!({"phase":"idle","stage":"VPN отключён","snapshot":null}),
        })
    })
}
fn lock() -> std::sync::MutexGuard<'static, Controller> {
    controller().lock().unwrap_or_else(|p| p.into_inner())
}
fn update(run: &Run, f: impl FnOnce(&mut Value)) {
    let mut c = lock();
    if c.generation == run.generation {
        f(&mut c.status);
    }
}
fn stage(run: &Run, text: &str) {
    update(run, |s| s["stage"] = text.into());
}
fn callback_string(run: &Run, method: &str, signature: &str, args: &[JValue]) -> Result<String> {
    let mut env = run.vm.attach_current_thread()?;
    let value = env
        .call_method(run.callbacks.as_obj(), method, signature, args)
        .with_context(|| format!("Android callback {method} failed"));
    if env.exception_check()? {
        env.exception_clear()?;
        bail!("Не удалось открыть защищённый профиль устройства");
    }
    let object = value?.l()?;
    if object.is_null() {
        return Ok(String::new());
    }
    let value = JString::from(object);
    let output = env.get_string(&value)?.into();
    Ok(output)
}
fn begin_registration(run: &Run) -> Result<()> {
    let mut env = run.vm.attach_current_thread()?;
    let mark = env.call_method(run.callbacks.as_obj(), "beginRegistration", "()V", &[]);
    if env.exception_check()? {
        env.exception_clear()?;
        bail!("Не удалось защитить регистрацию устройства");
    }
    mark?;
    Ok(())
}
fn read_identity(run: &Run) -> Result<Option<Identity>> {
    let secret = Zeroizing::new(callback_string(
        run,
        "loadIdentity",
        "()Ljava/lang/String;",
        &[],
    )?);
    if secret.is_empty() {
        return Ok(None);
    }
    Ok(Some(Identity::from_secret(secret.as_bytes())?))
}
fn save_identity(run: &Run, identity: &Identity) -> Result<()> {
    let secret = Zeroizing::new(serde_json::to_string(identity)?);
    let mut env = run.vm.attach_current_thread()?;
    let input = env.new_string(secret.as_str())?;
    let result = env.call_method(
        run.callbacks.as_obj(),
        "saveIdentity",
        "(Ljava/lang/String;)V",
        &[JValue::Object(input.as_ref())],
    );
    if env.exception_check()? {
        env.exception_clear()?;
        bail!("Не удалось сохранить защищённый профиль устройства");
    }
    result?;
    Ok(())
}
pub(crate) fn create_tun(vip: Ipv4Addr) -> Result<i32> {
    let run = lock()
        .current
        .clone()
        .context("VPN session is not running")?;
    ensure!(!run.stop.load(Ordering::Relaxed), "VPN setup cancelled");
    let mut env = run.vm.attach_current_thread()?;
    let ip = env.new_string(vip.to_string())?;
    let result = env.call_method(
        run.callbacks.as_obj(),
        "createTun",
        "(Ljava/lang/String;)I",
        &[JValue::Object(ip.as_ref())],
    );
    if env.exception_check()? {
        env.exception_clear()?;
        bail!("Android не разрешил создать VPN-интерфейс");
    }
    let fd = result?.i()?;
    if run.stop.load(Ordering::Relaxed) {
        if fd >= 0 {
            unsafe {
                libc::close(fd);
            }
        }
        bail!("VPN setup cancelled");
    }
    Ok(fd)
}
fn worker(run: Arc<Run>, node_name: String, force_relay: bool, commands: mpsc::Receiver<Command>) {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||->Result<()> {
        android_tap::reset_peers();
        let retained=lock().issued.clone();
        let stored=if retained.is_none(){read_identity(&run)?}else{None};
        let mut identity=match stored.or(retained) {
            Some(identity)=>identity,
            None=> {
                stage(&run,"Регистрация устройства");

                let identity=Session::provision_controlled(SERVER_MODULUS,&node_name,DEFAULT_BOOTSTRAP_HOST,
                    &ReportDirectory::disabled(),&mut || begin_registration(&run),Some(run.stop.clone()),&mut |progress| {
                        stage(&run,match progress { ProvisionProgress::Registering=>"Регистрация устройства",
                            ProvisionProgress::ReceivingIdentity=>"Сохранение профиля",ProvisionProgress::Complete=>"Подключение к серверу",
                            ProvisionProgress::Retry {..}=>"Повтор подключения",_=>"Авторизация устройства" });
                    })?;
                {lock().issued=Some(identity.clone());}
                save_identity(&run,&identity)?;
                {lock().issued=None;}
                identity
            }
        };
        identity.node_name=node_name;
        save_identity(&run,&identity)?;
        {lock().issued=None;}
        update(&run,|s|{s["device_name"]=identity.node_name.clone().into();s["rid"]=identity.rid.into();});
        update(&run, |s| s["retryable"] = true.into());
        stage(&run,"Подключение к серверу Radmin VPN");
        runtime::run(identity,SERVER_MODULUS.to_vec(),Options {force_relay,..Default::default()},commands,
            run.stop.clone(),|event|match event {
                Update::State(snapshot)=> {
                    let failure=snapshot.interface_error.clone();
                    update(&run,|s|{
                    s["phase"]=if failure.is_some() { "error" } else if run.stop.load(Ordering::Relaxed) { "stopping" } else if snapshot.interface_ready { "connected" } else {"connecting"}.into();
                    if let Some(error)=&failure {s["error"]=error.clone().into();s["retryable"]=false.into();}
                    s["stage"]=if failure.is_some() {"Не удалось настроить VPN"} else if snapshot.interface_ready {"VPN подключён"} else {"Настройка VPN"}.into();
                    s["snapshot"]=serde_json::to_value(snapshot).unwrap_or(Value::Null);
                    });
                    if failure.is_some() {run.stop.store(true,Ordering::Relaxed);}
                },
                Update::Operation {message,error}=>update(&run,|s|s["operation"]=json!({"id":0,"message":message,"error":error})),
                Update::CommandResult {id,message,error,..}=>{
                    let uncertain=error && (message.contains("timed out") || message.contains("timeout"));
                    update(&run,|s|s["operation"]=json!({"id":id,"message":message,"error":error,"uncertain":uncertain}));
                    // Reload memberships after an ambiguous mutation rather than retrying it blindly.
                    if uncertain { update(&run, |s| s["retryable"] = false.into()); run.stop.store(true,Ordering::Relaxed); }
                },
                Update::Ping {peer,id,rtt_ms,error}=>update(&run,|s|s["ping"]=json!({"peer":peer,"id":id,"rtt_ms":rtt_ms,"error":error})),
                Update::Catalog {..}=>{},
            })
    })).unwrap_or_else(|_|Err(anyhow::anyhow!("Внутренняя ошибка сетевого движка")));
    let cancelled = run.stop.load(Ordering::Relaxed);
    let failure = result
        .err()
        .filter(|_| !cancelled)
        .map(|e| format!("{e:#}"));
    {
        let mut c = lock();
        if c.generation == run.generation {
            let failed = failure.is_some() || c.status["phase"] == "error";
            c.status["phase"] = if failed { "error" } else { "idle" }.into();
            c.status["stage"] = if failed {
                "Подключение не удалось"
            } else {
                "VPN отключён"
            }
            .into();
            if let Some(error) = failure {
                c.status["error"] = error.into();
            }
            if let Some(snapshot) = c.status.get_mut("snapshot").filter(|s| !s.is_null()) {
                snapshot["interface_ready"] = false.into();
            }
            c.current = None;
        }
    }
    if let Ok(mut env) = run.vm.attach_current_thread() {
        let _ = env.call_method(
            run.callbacks.as_obj(),
            "engineEnded",
            "(J)V",
            &[JValue::Long(run.generation as i64)],
        );
        let _ = env.exception_clear();
    }
}
fn start(env: &mut JNIEnv, callback: JObject, name: JString, relay: bool) -> Result<()> {
    let name: String = env.get_string(&name)?.into();
    ensure!(
        !name.trim().is_empty()
            && name.encode_utf16().count() <= 128
            && !name.chars().any(char::is_control),
        "Укажите имя устройства (до 128 символов)"
    );
    let mut c = lock();
    ensure!(
        c.current.is_none() && c.worker.as_ref().is_none_or(|w| w.is_finished()),
        "Предыдущее подключение ещё завершается"
    );
    if let Some(worker) = c.worker.take() {
        let _ = worker.join();
    }
    c.generation += 1;
    let (sender, receiver) = mpsc::sync_channel(64);
    let run = Arc::new(Run {
        generation: c.generation,
        stop: Arc::new(AtomicBool::new(false)),
        commands: sender,
        ids: AtomicU64::new(1),
        vm: env.get_java_vm()?,
        callbacks: env.new_global_ref(callback)?,
    });
    c.status = json!({"phase":"connecting","stage":"Подключение к Radmin VPN","snapshot":null,"generation":c.generation});
    c.current = Some(run.clone());
    match thread::Builder::new()
        .name("bluenet-engine".into())
        .spawn(move || worker(run, name, relay, receiver))
    {
        Ok(worker) => {
            c.worker = Some(worker);
            Ok(())
        }
        Err(error) => {
            c.current = None;
            c.status["phase"] = "error".into();
            c.status["error"] = error.to_string().into();
            Err(error.into())
        }
    }
}

#[derive(Deserialize)]
struct Action {
    action: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    password: Option<String>,
    #[serde(default)]
    rid: u64,
}
impl Drop for Action {
    fn drop(&mut self) {
        if let Some(password) = &mut self.password {
            password.zeroize();
        }
    }
}
fn command(input: &str) -> Result<u64> {
    let mut action: Action = serde_json::from_str(input)?;
    let request = match action.action.as_str() {
        "join" => Command::Network(NetworkRequest::Join {
            name: action.name.clone(),
            password: action
                .password
                .take()
                .filter(|p| !p.is_empty())
                .map(NetworkPassword::new)
                .transpose()?,
        }),
        "create" => Command::Network(NetworkRequest::Create {
            name: action.name.clone(),
            password: NetworkPassword::new(action.password.take().context("Укажите пароль сети")?)?,
        }),
        "leave" => Command::Network(NetworkRequest::Leave {
            network: action.name.clone(),
        }),
        "ping" => Command::Ping { peer: action.rid },
        "retry" => Command::RetryPeers,
        _ => bail!("Неизвестная команда"),
    };
    let run = lock().current.clone().context("Сначала подключите VPN")?;
    ensure!(!run.stop.load(Ordering::Relaxed), "VPN отключается");
    let id = run.ids.fetch_add(1, Ordering::Relaxed);
    run.commands
        .try_send(Command::Tagged {
            id,
            command: Box::new(request),
        })
        .map_err(|_| anyhow::anyhow!("Сетевая очередь занята, попробуйте позже"))?;
    Ok(id)
}
#[no_mangle]
pub extern "system" fn Java_ru_bluenet_vpn_NativeBridge_start(
    mut env: JNIEnv,
    _: JClass,
    callback: JObject,
    name: JString,
    relay: jboolean,
) -> jboolean {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start(&mut env, callback, name, relay != 0)
    })) {
        Ok(Ok(())) => 1,
        Ok(Err(e)) => {
            let message = format!("{e:#}");
            {
                let mut c = lock();
                if c.current.is_none() {
                    c.status["phase"] = "error".into();
                    c.status["error"] = message.clone().into();
                }
            }
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            0
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Внутренняя ошибка запуска VPN",
            );
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_ru_bluenet_vpn_NativeBridge_stop(_: JNIEnv, _: JClass) {
    let mut c = lock();
    if let Some(run) = &c.current {
        run.stop.store(true, Ordering::Relaxed);
        c.status["phase"] = "stopping".into();
        c.status["stage"] = "Отключение VPN".into();
    }
}
#[no_mangle]
pub extern "system" fn Java_ru_bluenet_vpn_NativeBridge_status(env: JNIEnv, _: JClass) -> jstring {
    let status = lock().status.to_string();
    env.new_string(status)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_ru_bluenet_vpn_NativeBridge_command(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<u64> {
        let input = Zeroizing::new(String::from(env.get_string(&input)?));
        ensure!(input.len() <= 16384, "Команда слишком длинная");
        command(&input)
    }));
    match result {
        Ok(Ok(id)) => id as jlong,
        Ok(Err(e)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("{e:#}"));
            -1
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "Не удалось отправить команду",
            );
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_bluenet_vpn_NativeBridge_nonblocking(
    mut env: JNIEnv,
    _: JClass,
    fd: jni::sys::jint,
) {
    // FD is a temporary Java dup; changing open-file flags affects the owned socket too.
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        let _ = env.throw_new(
            "java/io/IOException",
            "Could not configure packet descriptor",
        );
    }
}
