File log = new File(basedir, "build.log")
assert log.isFile()
assert log.getText("UTF-8").toLowerCase().contains("cycle")
assert !new File(basedir, "target/architecture/architecture.mmd").exists()
