import Foundation
import Network

/// Small Bonjour-discoverable control listener for receiver-initiated
/// sessions. It accepts one framed JSON request per connection, asks the
/// sender controller whether to answer it, and closes the control connection.
/// Video never travels over this listener.
/// Mutable state is confined to `queue`; the handler is main-actor isolated.
final class ReverseRequestServer: @unchecked Sendable {
    typealias Handler = @MainActor (ReverseConnectionRequest, String?)
        -> ReverseConnectionReply

    private let queue = DispatchQueue(label: "opendisplay.reverse-requests")
    private let identifier: String
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    /// Set on the main thread before `start`; invoked on the main actor.
    var handler: Handler?

    init(identifier: String) {
        self.identifier = identifier
    }

    func start(name: String) {
        queue.async { [weak self] in
            self?.startOnQueue(name: name)
        }
    }

    private func startOnQueue(name: String) {
        stopOnQueue()
        do {
            let listener = try NWListener(using: .tcp)
            var txt = NWTXTRecord()
            txt["id"] = identifier
            txt["pv"] = String(ReverseConnectionRequest.serviceVersion)
            listener.service = NWListener.Service(
                name: name, type: ReverseConnectionRequest.serviceType,
                domain: nil, txtRecord: txt)
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    Log.info("reverse requests: advertising as \(name)")
                case .failed(let error):
                    Log.info("reverse requests: listener failed (\(error))")
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            self.listener = listener
            listener.start(queue: queue)
        } catch {
            Log.info("reverse requests: could not start (\(error))")
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.stopOnQueue()
        }
    }

    private func stopOnQueue() {
        listener?.cancel()
        listener = nil
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
    }

    private func accept(_ connection: NWConnection) {
        let key = ObjectIdentifier(connection)
        connections[key] = connection
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.connections.removeValue(forKey: key)
            default:
                break
            }
        }
        connection.start(queue: queue)
        receiveLength(on: connection)
    }

    private func receiveLength(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            guard error == nil, !isComplete, let data, data.count == 4 else {
                self.close(connection)
                return
            }
            let length = data.reduce(0) { ($0 << 8) | Int($1) }
            guard length > 0, length <= 64 * 1024 else {
                self.close(connection)
                return
            }
            self.receivePayload(length: length, on: connection)
        }
    }

    private func receivePayload(length: Int, on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: length, maximumLength: length) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            guard error == nil, !isComplete, let data, data.count == length,
                  let request = ReverseConnectionRequest(jsonData: data) else {
                self.close(connection)
                return
            }
            let host = self.remoteHost(of: connection)
            Task { @MainActor [weak self] in
                guard let self else { return }
                let reply = self.handler?(request, host) ?? .rejected(.disabled)
                self.queue.async {
                    self.send(reply, on: connection)
                }
            }
        }
    }

    private func send(_ reply: ReverseConnectionReply, on connection: NWConnection) {
        let payload = reply.jsonData
        guard !payload.isEmpty else {
            close(connection)
            return
        }
        var length = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { [weak self] _ in
            self?.close(connection)
        })
    }

    private func remoteHost(of connection: NWConnection) -> String? {
        let endpoint = connection.currentPath?.remoteEndpoint ?? connection.endpoint
        guard case let .hostPort(host, _) = endpoint else { return nil }
        return "\(host)"
    }

    private func close(_ connection: NWConnection) {
        connections.removeValue(forKey: ObjectIdentifier(connection))
        connection.cancel()
    }
}
