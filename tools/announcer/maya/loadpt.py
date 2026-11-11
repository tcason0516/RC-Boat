import zipfile, pickle, numpy as np
DT={'FloatStorage':np.float32,'HalfStorage':np.float16,'LongStorage':np.int64,'BFloat16Storage':None}
class St:
    def __init__(s,name):s.name=name
def load(path):
    z=zipfile.ZipFile(path); names=z.namelist(); pre=names[0].split('/')[0]
    pk=[n for n in names if n.endswith('data.pkl')][0]
    class U(pickle.Unpickler):
        def find_class(s,mod,name):
            if name=='_rebuild_tensor_v2' or name=='_rebuild_tensor':
                def rb(storage,off,size,stride,*a):
                    dt,key=storage
                    raw=np.frombuffer(z.read(f'{pre}/data/{key}'),dtype=dt)
                    if len(size)==0: return raw[off:off+1].reshape(())
                    return np.lib.stride_tricks.as_strided(raw[off:],shape=size,strides=[x*raw.itemsize for x in stride]).copy()
                return rb
            if name=='OrderedDict':
                import collections;return collections.OrderedDict
            if mod.startswith('torch') and name.endswith('Storage'): return name
            if name=='_rebuild_parameter':
                return lambda d,rg,bh: d
            return super().find_class(mod,name)
        def persistent_load(s,pid):
            _,typ,key,loc,n=pid
            return (DT[typ],key)
    return U(z.open(pk)).load()
if __name__=='__main__':
    sd=load('snac/pytorch_model.bin')
    for k,v in sd.items():
        if k.startswith('decoder') or k.startswith('quantizer'): print(k,v.shape,v.dtype)
