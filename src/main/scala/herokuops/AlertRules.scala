package herokuops

/** The rules in words, built from the detector's constants so the text can't drift from the code. */
object AlertRules:
  val text: String =
    s"""Conditions are counted over the last ${Detector.WindowMinutes} minutes.
       |- crash: "State changed ... to crashed", H10 or R15 (1)
       |- releasefailed, buildfailed: from the app webhook (1)
       |- memory: R14 memory quota exceeded (${Detector.threshold(Signal.Memory)})
       |- timeout: H12 request timeout (${Detector.threshold(Signal.Timeout)})
       |- routererror: H11, H13, H14, H17, H19-H23, H99 (${Detector.threshold(Signal.RouterError)})
       |- servererror: 5xx from the app, at least ${Detector.ServerErrorMinimum} and ${(Detector.ServerErrorRatio * 100).toInt}% of requests
       |- apperror: ERROR or FATAL log lines (${Detector.threshold(Signal.AppError)})
       |- probedown: ${Detector.DownAfterFailures} probe failures in a row (one probe a minute); up again after ${Detector.UpAfterSuccesses} successes
       |Not problems: 4xx, slow requests, H15 idle connections, H18/H27 client disconnects, "Process exited with status N" (only "to crashed" counts).
       |An alert fires when a condition starts, at most once per ${Detector.Cooldown.toMinutes} minutes if it keeps flapping, and again after ${Detector.Repeat.toHours} hours if it never clears.""".stripMargin
