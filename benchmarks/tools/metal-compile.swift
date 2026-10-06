// Compiles every .metal file in a directory with Metal's runtime compiler (MTLDevice.makeLibrary(source:)), the call
// MoltenVK makes when a pipeline is created, so it needs no Xcode Metal toolchain. Used by the shader compile check
// (ShaderPackCompile, macOS hosts) on the MSL that SPIRV-Cross produced for every pack stage.
//
//   swift benchmarks/tools/metal-compile.swift <dir>
//
// Prints one "FAIL <file>: <first error>" line per failing file, then "OK <passed> <total>".
import Foundation
import Metal

guard CommandLine.arguments.count == 2, let device = MTLCreateSystemDefaultDevice() else {
    FileHandle.standardError.write("usage: metal-compile.swift <dir> (needs a Metal device)\n".data(using: .utf8)!)
    exit(2)
}
let dir = URL(fileURLWithPath: CommandLine.arguments[1])
let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
    .filter { $0.pathExtension == "metal" }.sorted { $0.path < $1.path }
let options = MTLCompileOptions()
// MSL 3.1, as SPIRV-Cross was asked to emit (MetalTranslation.MSL_VERSION).
options.languageVersion = .version3_1
var failures = [String?](repeating: nil, count: files.count)
let lock = NSLock()
DispatchQueue.concurrentPerform(iterations: files.count) { i in
    let file = files[i]
    var result: String? = "unreadable"
    if let source = try? String(contentsOf: file, encoding: .utf8) {
        do {
            _ = try device.makeLibrary(source: source, options: options)
            result = nil
        } catch {
            // The first error line, e.g. "program_source:137:10: error: expected unqualified-id".
            let lines = "\(error)".split(separator: "\n").map(String.init)
            let line = lines.first { $0.contains("error:") } ?? lines.first ?? "unknown"
            result = line.range(of: "error: ").map { String(line[$0.upperBound...]) } ?? line
        }
    }
    lock.lock()
    failures[i] = result
    lock.unlock()
}
for (file, failure) in zip(files, failures) {
    if let failure = failure {
        print("FAIL \(file.deletingPathExtension().lastPathComponent): \(failure)")
    }
}
print("OK \(failures.filter { $0 == nil }.count) \(files.count)")
