//! 无头 QA 驱动:批量载入存档 → 驱动 VM 数帧 → 周期截图 → 记录场景/错误。
//!
//! 用法:
//!   qa_driver --project <游戏目录> [--saves 0-99] [--frames-per-save 900]
//!             [--click-every 150] [--skip-hold] [--out <报告目录>]
//!
//! 每个存档:全新 VM 引导 → request_runtime_load → 跑帧(可选按住 Ctrl 跳过)
//! → 周期 capture_frame_rgba 落 PNG → 记录最终场景/行号/错误 → report.json

use anyhow::{Context, Result};
use siglus_assets::scene_pck::ScenePck;
use siglus_scene_vm::runtime::input::VmMouseButton;
use siglus_scene_vm::runtime::CommandContext;
use siglus_scene_vm::runtime::RuntimeSaveKind;
use siglus_scene_vm::scene_stream::SceneStream;
use siglus_scene_vm::vm::{SceneVm, VmConfig};
use std::path::{Path, PathBuf};

struct Args {
    project: PathBuf,
    saves: Vec<usize>,
    frames_per_save: usize,
    click_every: usize,
    skip_hold: bool,
    stall_click_after: usize,
    out: PathBuf,
}

fn parse_args() -> Args {
    let mut project = None;
    let mut saves = (0..100).collect::<Vec<_>>();
    let mut frames_per_save = 900;
    let mut click_every = 150;
    let mut skip_hold = false;
    let mut stall_click_after = 420;
    let mut out = PathBuf::from("qa_report");
    let mut argv = std::env::args().skip(1);
    while let Some(a) = argv.next() {
        match a.as_str() {
            "--project" => project = argv.next().map(PathBuf::from),
            "--saves" => {
                let spec = argv.next().unwrap_or_default();
                saves = if let Some((a, b)) = spec.split_once('-') {
                    (a.parse::<usize>().unwrap_or(0)..=b.parse::<usize>().unwrap_or(99))
                        .collect()
                } else {
                    spec.split(',')
                        .filter_map(|s| s.parse().ok())
                        .collect::<Vec<_>>()
                };
            }
            "--frames-per-save" => frames_per_save = argv.next().and_then(|v| v.parse().ok()).unwrap_or(900),
            "--click-every" => click_every = argv.next().and_then(|v| v.parse().ok()).unwrap_or(150),
            "--skip-hold" => skip_hold = true,
            "--stall-click-after" => {
                stall_click_after = argv.next().and_then(|v| v.parse().ok()).unwrap_or(420)
            }
            "--out" => out = argv.next().map(PathBuf::from).unwrap_or(out),
            other => eprintln!("unknown arg {other}"),
        }
    }
    Args {
        project: project.unwrap_or_else(|| PathBuf::from(".")),
        saves,
        frames_per_save,
        click_every,
        skip_hold,
        stall_click_after,
        out,
    }
}

fn make_vm(project: &Path) -> Result<SceneVm<'static>> {
    let pck_path = siglus_scene_vm::resource::find_scene_pck_path(project)?;
    let opt = siglus_scene_vm::resource::load_scene_pck_decode_options(project)?;
    let pack = ScenePck::load_and_rebuild(&pck_path, &opt)?;
    let scn_no = pack
        .find_scene_no("_system_start")
        .context("boot scene _system_start not found")?;
    let (owner, range) = pack.scn_data_shared(scn_no)?;
    let mut stream = SceneStream::new_shared_range_with_string_codec(
        owner,
        range,
        pack.string_codec,
    )?;
    stream.jump_to_z_label(0)?;
    let mut ctx = CommandContext::new(project.to_path_buf());
    ctx.screen_w = 1280;
    ctx.screen_h = 960;
    let mut vm = SceneVm::with_config(VmConfig::from_env(), stream, ctx);
    vm.cfg.max_steps = 1_000_000;
    vm.restart_scene_name("_system_start", 0)?;
    Ok(vm)
}

fn click(vm: &mut SceneVm<'static>, x: i32, y: i32) {
    vm.ctx.on_mouse_move(x, y);
    vm.ctx.on_mouse_down(VmMouseButton::Left);
    vm.ctx.on_mouse_up(VmMouseButton::Left);
}

fn save_png(vm: &mut SceneVm<'static>, path: &Path) {
    match vm.ctx.capture_frame_rgba() {
        Ok(img) => {
            if let Err(e) = img.save(path) {
                eprintln!("[qa] screenshot save failed {}: {e}", path.display());
            }
        }
        Err(e) => eprintln!("[qa] capture failed: {e:#}"),
    }
}

fn main() -> Result<()> {
    let args = parse_args();
    std::fs::create_dir_all(args.out.join("shots"))?;
    let mut report: Vec<serde_json::Value> = Vec::new();

    for &n in &args.saves {
        let started = std::time::Instant::now();
        let mut entry = serde_json::json!({
            "save": n,
            "status": "unknown",
            "load_completed": false,
            "error": null,
            "final_scene": null,
            "final_line": null,
            "stalls": 0,
        });
        eprintln!("[qa] ===== save {n:04} =====");
        let result = (|| -> Result<()> {
            let mut vm = make_vm(&args.project)?;
            // 引导若干帧到达可安全受理读档请求的状态
            for f in 0..60 {
                vm.run_script_proc()?;
                vm.tick_frame()?;
                let _ = f;
            }
            vm.ctx
                .request_runtime_load(RuntimeSaveKind::Normal, n);
            if args.skip_hold {
                vm.ctx.on_key_down(siglus_scene_vm::runtime::input::VmKey::Control);
            }
            let mut stall = 0usize;
            let mut last_scene: Option<String> = None;
            let mut last_line: Option<i64> = None;
            for f in 0..args.frames_per_save {
                vm.run_script_proc()?;
                if vm.take_runtime_load_completed() && !entry["load_completed"].as_bool().unwrap() {
                    entry["load_completed"] = serde_json::json!(true);
                    eprintln!("[qa] save {n:04}: load completed at frame {f}");
                }
                vm.tick_frame()?;
                if f % args.click_every == args.click_every - 1 {
                    click(&mut vm, 640, 480);
                }
                // 卡死检测:场景与行号长时间不变 → 截图 + 中心点击一次
                let scene = vm.current_scene_name().map(str::to_owned);
                let line = vm.current_line_no();
                if scene == last_scene && line == last_line {
                    stall += 1;
                } else {
                    stall = 0;
                }
                last_scene = scene.clone();
                last_line = Some(line);
                if stall == args.stall_click_after {
                    entry["stalls"] = serde_json::json!(
                        entry["stalls"].as_i64().unwrap_or(0) + 1
                    );
                    save_png(&mut vm, &args.out.join("shots").join(format!("save{n:04}_stall.png")));
                    click(&mut vm, 640, 480);
                }
                if f == args.frames_per_save / 3 || f == args.frames_per_save * 2 / 3 {
                    save_png(
                        &mut vm,
                        &args.out
                            .join("shots")
                            .join(format!("save{n:04}_f{f}.png")),
                    );
                }
            }
            entry["final_scene"] = serde_json::json!(last_scene);
            entry["final_line"] = serde_json::json!(last_line);
            save_png(
                &mut vm,
                &args.out.join("shots").join(format!("save{n:04}_end.png")),
            );
            Ok(())
        })();
        match result {
            Ok(()) => {
                if entry["status"] == "unknown" {
                    entry["status"] = serde_json::json!("ok");
                }
            }
            Err(e) => {
                entry["status"] = serde_json::json!("error");
                entry["error"] = serde_json::json!(format!("{e:#}"));
                eprintln!("[qa] save {n:04}: ERROR {e:#}");
            }
        }
        entry["elapsed_ms"] = serde_json::json!(started.elapsed().as_millis() as u64);
        report.push(entry.clone());
        let report_path = args.out.join("report.json");
        std::fs::write(
            &report_path,
            serde_json::to_string_pretty(&report).unwrap_or_default(),
        )
        .ok();
    }
    eprintln!("[qa] done, report at {}", args.out.join("report.json").display());
    Ok(())
}
