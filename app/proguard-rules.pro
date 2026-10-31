# R8 / 资源压缩规则（轻传 LiteSend）
# 没有任何反射驱动的序列化，绝大多数类都会被安全裁剪；这里只保留必要的属性。

-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**

# 前台服务由系统反射实例化（清单里有名字，但保险起见保留构造）
-keep class app.qingchuan.service.TransferService { <init>(...); }
