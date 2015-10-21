package ammonite.shell

import ammonite.interpreter.Classes
import ammonite.interpreter.Imports
import ammonite.interpreter.Interpreter
import ammonite.interpreter._
import ammonite.api.{ ClassLoaderType, IvyConstructor, Import, Bridge, DisplayItem, Decl }
import ammonite.shell.util._

import com.github.alexarchambault.ivylight.{Resolver, Ivy, ClasspathFilter}

import caseapp._

import java.io.{ Console => _, _ }
import fastparse.core.Result.Success
import org.apache.ivy.plugins.resolver.DependencyResolver

import scala.annotation.tailrec


// TODO Add options --predef-file, --no-scala-predef, --no-preimports, --hist-file

sealed trait ShellError {
  def msg: String
}
object ShellError {
  case object Exit extends ShellError {
    def msg = "Exiting"
  }
  case object Skip extends ShellError {
    def msg = "Incomplete"
  }
  case class ParseError(msg: String) extends ShellError
  case class InterpreterError(underlying: ammonite.api.InterpreterError) extends ShellError {
    def msg = underlying.msg
  }
}

trait ShellAction[T] { self =>
  def apply(shell: Shell): Either[ShellError, T]
  def map[U](f: T => U): ShellAction[U] = flatMap(t => ShellAction.point(f(t)))
  def flatMap[U](f: T => ShellAction[U]): ShellAction[U] =
    ShellAction.instance { shell =>
      self(shell).right.flatMap(f(_)(shell))
    }
}

object ShellAction {
  def point[T](t: T): ShellAction[T] = instance(_ => Right(t))

  def instance[T](f: Shell => Either[ShellError, T]): ShellAction[T] =
    new ShellAction[T] {
      def apply(shell: Shell) = f(shell)
    }

  val readTerm: ShellAction[(String, Seq[String])] =
    instance { shell =>
      shell.frontEnd().action(
        System.in, shell.reader, System.out,
        shell.colors().prompt + shell.prompt() + scala.Console.RESET + " ",
        shell.colors(),
        shell.interp.complete(_, _),
        shell.history,
        addHistory = (code) => if (code != "") {
          // storage().fullHistory() = storage().fullHistory() :+ code
          shell.history = shell.history :+ code
        }
      ) match {
        case Res.Success((code, statements)) => Right((code, statements))
        case Res.Exit => Left(ShellError.Exit)
        case Res.Skip => Left(ShellError.Skip)
        case Res.Failure(msg) => Left(ShellError.ParseError(msg))
      }
    }

  def handleInterruptions(handler: => Unit): ShellAction[Unit] =
    new ShellAction[Unit] {
      import sun.misc.{ Signal, SignalHandler }

      var oldSigInt = List.empty[SignalHandler]
      def handlers = {
        val handlersField = classOf[Signal].getDeclaredField("handlers")
        handlersField.setAccessible(true)
        handlersField.get(null).asInstanceOf[java.util.Hashtable[Signal, SignalHandler]]
      }

      def apply(shell: Shell) = Right(())
      override def flatMap[U](f: Unit => ShellAction[U]) =
        ShellAction.instance { shell =>
          val sig = new Signal("INT")
          oldSigInt = handlers.get(sig) :: oldSigInt
          Signal.handle(sig, new SignalHandler () {
            def handle(sig: Signal) = handler
          })

          try f(())(shell)
          finally {
            handlers.put(sig, oldSigInt.head)
            oldSigInt = oldSigInt.tail
          }
        }
    }

  def interpret(statements: Seq[String], compiled: => Unit): ShellAction[Evaluated[Unit]] =
    instance { shell =>
      shell.interp(statements, compiled, _.asInstanceOf[Iterator[String]].foreach(print))
        .left.map(ShellError.InterpreterError)
    }
}

class Shell(
  initialHistory: Seq[String],
  predef: String,
  classWrap: Boolean,
  sharedLoader: Boolean
) {

  val reader = new InputStreamReader(System.in)

  var history = new History(initialHistory.toVector)

  val frontEnd = Ref[FrontEnd](FrontEnd.Ammonite)
  val prompt = Ref("@")
  val colors = Ref[Colors](Colors.Default)

  val pprintConfig = pprint.Config.Colors.PPrintConfig

  val interp: ammonite.api.Interpreter with InterpreterInternals =
    Ammonite.newInterpreter(
      predef,
      classWrap,
      pprintConfig.copy(width = frontEnd().width, height = frontEnd().height),
      colors(),
      sharedLoader,
      prompt,
      () => ???,
      initialHistory
    )

}

case class Ammonite(
  initialPrompt: String = "@",
  predef: String,
  wrap: String,
  histFile: String = new File(System.getProperty("user.home"), ".amm") .toString,
  sharedLoader: Boolean = false
) extends App {

  println("Loading Ammonite Shell...")

  val delimiter = "\n\n\n"

  val saveFileOpt = Some(histFile).filter(_.nonEmpty).map(new File(_))

  val initialHistory =
    saveFileOpt .fold(Seq.empty[String]) { saveFile =>
      try scala.io.Source.fromFile(saveFile).mkString.split(delimiter)
      catch { case e: FileNotFoundException => Nil }
    }

  val classWrap = wrap match {
    case "class" | "cls" | "" => true
    case "object" | "obj" => false
    case _ => Console.err.println(s"Unrecognized wrap argument: $wrap"); sys exit 255
  }

  val shell = new Shell(
    initialHistory,
    predef,
    classWrap,
    sharedLoader
  )

  import shell._

  // Run the predef. For now we assume that the whole thing is a single
  // command, and will get compiled & run at once. We hard-code the
  // line number to -1 if the predef exists so the first user-entered
  // line becomes 0
  if (predef.nonEmpty)
    Parsers.split(predef) match {
      case Some(Success(stmts, _)) =>
        interp(stmts, (), _.asInstanceOf[Iterator[String]].foreach(print))
        // FIXME Handle errors
        print("\n")
      case other =>
        println(s"Error while running predef: $other")
    }

  val saveHistory = saveFileOpt.fold((_: String) => ()) { saveFile => s =>
    val fw = new FileWriter(saveFile, true)
    try fw.write(delimiter + s)
    finally fw.close()
  }

  val readEvalPrint =
    for {
      (code, stmts) <- ShellAction.readTerm
                  _ <- ShellAction.handleInterruptions { Thread.currentThread().stop() }
                 ev <- ShellAction.interpret(stmts, saveHistory(code))
    } yield ev

  @tailrec final def loop(): Unit =
    readEvalPrint(shell) match {
      case Left(ShellError.Exit) =>
        println("Bye!")
        interp.stop()
      case Left(err) =>
        println(Console.RED + err.msg + Console.RESET)
        loop()
      case Right(_) =>
        loop()
    }

  loop()
}

object Ammonite extends AppOf[Ammonite] {
  val parser = default

  def bridgeConfig(
    startJars: Seq[File] = Nil,
    startIvys: Seq[(String, String, String)] = Nil,
    jarMap: File => File = identity,
    startResolvers: Seq[DependencyResolver] = Seq(Resolver.localRepo, Resolver.defaultMaven),
    shellPrompt: => Ref[String] = Ref("@"),
    reset: => Unit = (),
    pprintConfig: pprint.Config = pprint.Config.Defaults.PPrintConfig,
    colors: Colors = Colors.BlackWhite
  ): Bridge =
    new Bridge {
      def init = "object ReplBridge extends ammonite.shell.ReplAPIHolder"
      def name = "ReplBridge"
      
      def imports =
        NamesFor[ReplAPI].map { case (name, isImpl) =>
          Import(name, name, "", "ReplBridge.shell", isImpl)
        }.toSeq ++
        NamesFor[IvyConstructor.type].map { case (name, isImpl) =>
          Import(name, name, "", "ammonite.api.IvyConstructor", isImpl)
        }.toSeq

      def print(v: AnyRef) = v.asInstanceOf[Iterator[String]].foreach(print)

      var replApi: ReplAPI with FullReplAPI = null
      def reset0() = reset

      def initClass(intp: ammonite.api.Interpreter, cls: Class[_]) = {
        if (replApi == null)
          replApi = new ReplAPIImpl(
            intp,
            startJars,
            startIvys,
            jarMap,
            startResolvers,
            colors,
            shellPrompt,
            pprintConfig
          ) {
            def reset() = reset0()
          }

        ReplAPIHolder.initReplBridge(cls.asInstanceOf[Class[ReplAPIHolder]], replApi)
      }
    }

  def print0(items: Seq[DisplayItem]): String =
    s"ReplBridge.shell.Internal.combinePrints(${items.map(ShellDisplay(_)).mkString(", ")})"

  val scalaVersion = scala.util.Properties.versionNumberString
  val startIvys = Seq(
    ("org.scala-lang", "scala-library", scalaVersion),
    ("com.github.alexarchambault", s"ammonite-shell-api_$scalaVersion", BuildInfo.version)
  )
  val startCompilerIvys = startIvys ++ Seq(("org.scala-lang", "scala-compiler", scalaVersion))

  val resolvers =
    Seq(Resolver.localRepo, Resolver.defaultMaven) ++ {
      if (BuildInfo.version endsWith "-SNAPSHOT") Seq(Resolver.sonatypeRepo("snapshots")) else Seq()
    }

  lazy val packJarMap = Classes.jarMap(getClass.getClassLoader)

  lazy val mainStartPaths =
    Ivy.resolve(startIvys, resolvers).toSeq
      .map(packJarMap)
      .filter(_.exists())

  lazy val macroStartPaths =
    Ivy.resolve(startCompilerIvys, resolvers).toSeq
      .map(packJarMap)
      .filter(_.exists())


  lazy val startClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ mainStartPaths).toSet)

  lazy val startCompilerClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ macroStartPaths).toSet)


  def hasObjWrapSpecialImport(d: Decl): Boolean =
    d.display.exists {
      case DisplayItem.Import("special.wrap.obj") => true
      case _                                      => false
    }

  def newInterpreter(
    predef: String,
    classWrap: Boolean,
    pprintConfig: pprint.Config,
    colors: Colors,
    sharedLoader: Boolean,
    shellPromptRef: => Ref[String] = Ref("@"),
    reset: => Unit = (),
    initialHistory: Seq[String] = Nil
  ): ammonite.api.Interpreter with InterpreterInternals = {
    val startPaths = Classes.defaultPaths()

    new Interpreter(
      bridgeConfig(
        startJars = if (sharedLoader) startPaths(ClassLoaderType.Main) else mainStartPaths,
        startIvys = startIvys,
        startResolvers = resolvers,
        jarMap = packJarMap,
        shellPrompt = shellPromptRef,
        reset = reset,
        pprintConfig = pprintConfig,
        colors = colors
      ),
      imports = new Imports(useClassWrapper = classWrap),
      classes =
        if (sharedLoader)
          new Classes(
            Thread.currentThread().getContextClassLoader,
            startPaths
          )
        else
          new Classes(
            startClassLoader,
            Map(
              ClassLoaderType.Main -> mainStartPaths,
              ClassLoaderType.Macro -> macroStartPaths,
              ClassLoaderType.Plugin -> mainStartPaths
            ),
            startCompilerClassLoader = startCompilerClassLoader
          ),
      startingLine = if (predef.nonEmpty) -1 else 0,
      initialHistory = initialHistory
    ) {
      override def wrap(
        decls: Seq[Decl],
        imports: String,
        unfilteredImports: String,
        wrapper: String
      ) = {
        val (doClassWrap, decls0) =
          if (classWrap && decls.exists(hasObjWrapSpecialImport))
            (false, decls.filterNot(hasObjWrapSpecialImport))
          else
            (classWrap, decls)

        if (doClassWrap)
          Interpreter.classWrap(print0, decls0, imports, unfilteredImports, wrapper)
        else
          Interpreter.wrap(print0, decls0, imports, unfilteredImports, wrapper)
      }
    }
  }

}
