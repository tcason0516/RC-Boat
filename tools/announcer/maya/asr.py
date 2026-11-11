import sys, subprocess, numpy as np, sherpa_onnx
d='/tmp/asr/sherpa-onnx-whisper-base.en/'
rec=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=d+'base.en-encoder.int8.onnx',decoder=d+'base.en-decoder.int8.onnx',tokens=d+'base.en-tokens.txt',num_threads=2)
for f in sys.argv[1:]:
    a=np.frombuffer(subprocess.run(['ffmpeg','-loglevel','error','-i',f,'-ar','16000','-ac','1','-f','f32le','-'],capture_output=True).stdout,np.float32)
    s=rec.create_stream(); s.accept_waveform(16000,a); rec.decode_stream(s)
    print(f, '|', s.result.text.strip(), '| peak %.2f rms %.3f'%(np.abs(a).max(), np.sqrt((a**2).mean())))
