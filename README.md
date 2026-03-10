# MVPPRP

当前项目只保留了使用 CPLEX 求解 `OriginalModel` 的部分。

项目执行需要用到 CPLEX，我安装的版本是 `CPLEX_Studio2211`。

运行单个实例：

```sh
export MAVEN_OPTS="-Djava.library.path=/Applications/CPLEX_Studio2211/cplex/bin/arm64_osx"
mvn compile exec:java -Dexec.mainClass="Main"
```

运行多个实例：

```sh
export MAVEN_OPTS="-Djava.library.path=/Applications/CPLEX_Studio2211/cplex/bin/arm64_osx"
mvn compile exec:java -Dexec.mainClass="Main" -Dexec.args="data/MVPRP/MVPRP1_10_3_2.txt data/MVPRP/MVPRP2_10_3_2.txt data/MVPRP/MVPRP3_10_3_2.txt data/MVPRP/MVPRP4_10_3_2.txt"
```

输入数据会先按 [instance.tex](/Users/tangshenghao/IdeaProjects/MVPPRP_ALNS/instance.tex) 中说明的语义映射载入，再交给 `OriginalModelSolver` 建模求解。
