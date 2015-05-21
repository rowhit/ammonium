package ammonite.interpreter

import java.lang.reflect.InvocationTargetException

import acyclic.file

import scala.collection.mutable
import scala.reflect.io.VirtualDirectory
import scala.util.Try
import scala.util.control.ControlThrowable

import ammonite.api.{ DisplayItem, Decl, BridgeConfig, ImportData }


object Wrap {
  val default = apply(_.map {
    case DisplayItem.Definition(label, name) => s"""println("defined $label $name")"""
    case DisplayItem.Import(imported) => s"""println("import $imported")"""
    case DisplayItem.Identity(ident) => s"""println("$ident = " + $$user.$ident)"""
    case DisplayItem.LazyIdentity(ident) => s"""println("$ident = <lazy>")"""
  } .mkString(" ; "))

  def hasObjWrapSpecialImport(d: Decl): Boolean = d.display.exists {
    case DisplayItem.Import("special.wrap.obj") => true
    case _ => false
  }

  def apply(displayCode: Seq[DisplayItem] => String, classWrap: Boolean = false) = {
    (initialDecls: Seq[Decl], previousImportBlock: String, initialWrapperName: String) =>
      val (doClassWrap, decls, wrapperName) = {
        if (classWrap && initialDecls.exists(hasObjWrapSpecialImport))
          (false, initialDecls.filterNot(hasObjWrapSpecialImport), "specialObj" + initialWrapperName.capitalize)
        else
          (classWrap, initialDecls, initialWrapperName)
      }

      val code = decls.map(_.code) mkString " ; "
      val mainCode = displayCode(decls.flatMap(_.display))

      wrapperName -> {
        if (doClassWrap)
          s"""
            object $wrapperName$$Main {
              $previousImportBlock // FIXME Only import implicits here

              def $$main() = {val $$user: $wrapperName.INSTANCE.$$user.type = $wrapperName.INSTANCE.$$user; $mainCode}
            }


            object $wrapperName {
              val INSTANCE = new $wrapperName
            }

            class $wrapperName extends _root_.java.io.Serializable {
              $previousImportBlock // FIXME Only import necessary imports here (implicits ones + the ones referenced in code)

              class $$user extends _root_.java.io.Serializable {
                $code
              }

              val $$user = new $$user
            }
         """
        else
          s"""$previousImportBlock

              object $wrapperName$$Main {
                def $$main() = {val $$user: $wrapperName.type = $wrapperName; $mainCode}
              }

              object $wrapperName {
                $code
              }
           """
      }
  }
}


/**
 * Thrown to exit the interpreter cleanly
 */
case object Exit extends ControlThrowable

trait InterpreterInternals {

  def apply[T](line: String,
               saveHistory: (String => Unit, String) => Unit = _(_),
               printer: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T],
               stdout: Option[String => Unit] = None,
               stderr: Option[String => Unit] = None): Res[Evaluated[T]]

  // def evalClass(code: String, wrapperName: String) // return type???
  def process[T](input: Seq[Decl], process: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]): Res[Evaluated[T]]
  def handleOutput(res: Res[Evaluated[_]]): Boolean

}

/**
 * A convenient bundle of all the functionality necessary
 * to interpret Scala code.
 */
class Interpreter(val bridgeConfig: BridgeConfig = BridgeConfig.empty,
                  val wrapper: (Seq[Decl], String, String) => (String, String) = Wrap.default,
                  val imports: ammonite.api.Imports = new Imports(),
                  val classes: ammonite.api.Classes = new Classes(),
                  startingLine: Int = 0,
                  initialHistory: Seq[String] = Nil,
                  enableCompilerPlugins: Boolean = true) extends ammonite.api.Interpreter with InterpreterInternals {

  var currentCompilerOptions = List.empty[String]

  def updateImports(newImports: Seq[ImportData]): Unit = {
    imports.update(newImports)

    /* This is required by the use of WeakTypeTag in the printers,
       whose implicits get replaced by calls to implicitly */
    if (currentCompilerOptions.contains("-Yno-imports")) {
      // FIXME And -Yno-predef too?
      // FIXME Remove the import when the option is dropped
      imports.update(Seq(
        ImportData("implicitly", "implicitly", "", "scala.Predef", isImplicit = true /* Forces the import even if there's no explicit reference to it */)
      ))
    }
  }

  updateImports(bridgeConfig.imports)

  val dynamicClasspath = new VirtualDirectory("(memory)", None)

  val history = initialHistory.to[collection.mutable.Buffer]
  var buffered = ""

  var sourcesMap = new mutable.HashMap[String, String]
  def sources = sourcesMap.toMap

  /**
   * The current line number of the REPL, used to make sure every snippet
   * evaluated can have a distinct name that doesn't collide.
   */
  var currentLine = startingLine

  def getCurrentLine = currentLine.toString.replace("-", "_")


  def complete(snippetIndex: Int, snippet: String, previousImports: String = null) = {
    pressy.complete(snippetIndex, Option(previousImports) getOrElse imports.previousImportBlock(), snippet)
  }

  def decls(code: String) = {
    Preprocessor(compiler.parse, code, getCurrentLine) match {
      case Res.Success(l) =>
        Right(l)
      case Res.Buffer(s) =>
        throw new IllegalArgumentException(s"Incomplete statement: $s")
      case Res.Exit =>
        throw new Exception("Can't happen")
      case Res.Skip =>
        Right(Nil)
      case Res.Failure(err) =>
        Left(err)
    }
  }

  def compile(src: Array[Byte], runLogger: String => Unit) = {
    compiler.compile(src, runLogger)
  }

  def run(code: String) = {
    apply(code, (_, _) => (), bridgeConfig.defaultPrinter) match {
      case Res.Success(ev) =>
        updateImports(ev.imports)
        Right(())
      case Res.Buffer(s) =>
        throw new IllegalArgumentException(s"Incomplete statement: $s")
      case Res.Exit =>
        throw Exit
      case Res.Skip =>
        Right(())
      case Res.Failure(err) =>
        Left(err)
    }
  }

  def apply[T](line: String,
               saveHistory: (String => Unit, String) => Unit = _(_),
               printer: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T],
               stdout: Option[String => Unit] = None,
               stderr: Option[String => Unit] = None) =
    for{
      _ <- Catching { case Ex(x@_*) =>
        val Res.Failure(trace) = Res.Failure(x)
        Res.Failure(trace + "\nSomething unexpected went wrong =(")
      }
      p <- Preprocessor(compiler.parse, line, getCurrentLine)
      _ = saveHistory(history.append(_), line)
      _ <- Capturing(stdout, stderr)
      out <- process(p, printer)
    } yield out

  def evalClass(code: String, wrapperName: String) = for {
    (output, compiled) <- Res.Success {
      val output = mutable.Buffer.empty[String]
      val c = compiler.compile(code.getBytes, output.append(_))
      (output, c)
    }

    (classFiles, importData) <- Res[(Traversable[(String, Array[Byte])], Seq[ImportData])](
      compiled, "Compilation Failed\n" + output.mkString("\n")
    )

    cls <- Res[Class[_]](Try {
      for ((name, bytes) <- classFiles) classes.addClass(name, bytes)
      Class.forName(wrapperName, true, classes.currentClassLoader)
    }, e => "Failed to load compiled class " + e)
  } yield (cls, importData)

  def interrupted() = {
    Thread.interrupted()
    Res.Failure("\nInterrupted!")
  }

  type InvEx = InvocationTargetException
  type InitEx = ExceptionInInitializerError

  /**
   * Takes the preprocessed `code` and `printCode` and compiles/evals/runs/etc.
   * it to provide a result. Takes `printer` as a callback, instead of returning
   * the `Iterator` as part of the output, because printing can cause side effects
   * (e.g. for Streams which are lazily printed) and can fail with an exception!
   * passing in the callback ensures the printing is still done lazily, but within
   * the exception-handling block of the `Evaluator`
   */
  def process[T](input: Seq[Decl], process: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]): Res[Evaluated[T]] = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try { Thread.currentThread().setContextClassLoader(classes.currentClassLoader)

      for {
        wrapperName0 <- Res.Success("cmd" + getCurrentLine)
        _ <- Catching{ case e: ThreadDeath => interrupted() }
        (wrapperName, wrappedLine) = wrapper(input, imports.previousImportBlock(input.flatMap(_.referencedNames).toSet), wrapperName0)
        (cls, newImports) <- evalClass(wrappedLine, wrapperName + "$Main")
        _ = currentLine += 1
        _ <- Catching{
          case Ex(_: InitEx, Exit)                => Res.Exit
          case Ex(_: InvEx, _: InitEx, Exit)      => Res.Exit
          case Ex(_: ThreadDeath)                 => interrupted()
          case Ex(_: InvEx, _: ThreadDeath)       => interrupted()
          case Ex(_: InvEx, _: InitEx, userEx@_*) => Res.Failure(userEx, stopMethod = "$main", stopClass = s"$wrapperName$$$$user")
          case Ex(userEx@_*)                      => Res.Failure(userEx, stopMethod = "evaluatorRunPrinter")
        }
      } yield {
        // Exhaust the printer iterator now, before exiting the `Catching`
        // block, so any exceptions thrown get properly caught and handled
        val value = evaluatorRunPrinter(process(cls.getDeclaredMethod("$main").invoke(null)))
        sourcesMap(wrapperName) = wrappedLine
        Evaluated(
          wrapperName,
          newImports.map(id => id.copy(
            wrapperName = wrapperName,
            prefix = if (id.prefix == "") wrapperName else id.prefix
          )),
          value
        )
      }

    } finally Thread.currentThread().setContextClassLoader(oldClassloader)
  }

  /**
   * Dummy function used to mark this method call in the stack trace,
   * so we can easily cut out the irrelevant part of the trace when
   * showing it to the user.
   */
  def evaluatorRunPrinter[T](f: => T): T = f

  def handleOutput(res: Res[Evaluated[_]]) = {
    res match{
      case Res.Skip =>
        buffered = ""
        true
      case Res.Buffer(line) =>
        /**
         * Hack to work around the fact that if nothing got entered into
         * the prompt, the `ConsoleReader`'s history wouldn't increase
         */
        buffered = line + "\n"
        true
      case Res.Exit =>
        pressy.shutdownPressy()
        false
      case Res.Success(ev) =>
        buffered = ""
        updateImports(ev.imports)
        true
      case Res.Failure(msg) =>
        buffered = ""
        true
    }
  }

  var compiler: Compiler = _
  var pressy: Pressy = _
  def init(options: Seq[String]) = {
    currentCompilerOptions = options.toList

    compiler = Compiler(
      Classes.bootStartJars ++ (if (_macroMode) classes.compilerJars else classes.jars),
      Classes.bootStartDirs ++ classes.dirs, // FIXME Add Classes.compilerDirs, use it here
      dynamicClasspath,
      currentCompilerOptions,
      classes.currentCompilerClassLoader,
      () => pressy.shutdownPressy(),
      enableCompilerPlugins = enableCompilerPlugins
    )
    pressy = Pressy(
      Classes.bootStartJars ++ (if (_macroMode) classes.compilerJars else classes.jars),
      Classes.bootStartDirs ++ classes.dirs, // FIXME Add Classes.compilerDirs, use it here too
      dynamicClasspath,
      classes.currentCompilerClassLoader
    )

    // initializing the compiler so that it does not complain having no phase
    compiler.compile("object $dummy".getBytes, _ => ())
  }
  def initBridge(): Unit = {
    bridgeConfig.initClass(this,
      evalClass(bridgeConfig.init, bridgeConfig.name).map(_._1) match {
        case Res.Success(s) => s
        case other => throw new Exception(s"Error while initializing REPL API: $other")
      }
    )
  }

  def stop() = {
    onStopHooks.foreach(_())
  }

  var onStopHooks = Seq.empty[() => Unit]
  def onStop(action: => Unit) = onStopHooks = onStopHooks :+ { () => action }

  init(currentCompilerOptions)
  initBridge()

  private var _macroMode = false
  def macroMode(): Unit = {
    if (!_macroMode) {
      _macroMode = true
      classes.useMacroClassLoader(true)
      init(currentCompilerOptions)
      initBridge()
    }
  }
}

